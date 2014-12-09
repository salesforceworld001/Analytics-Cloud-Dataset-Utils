/*
 * Copyright (c) 2014, salesforce.com, inc.
 * All rights reserved.
 * 
 * Redistribution and use in source and binary forms, with or without modification, are permitted provided 
 * that the following conditions are met:
 * 
 *    Redistributions of source code must retain the above copyright notice, this list of conditions and the 
 *    following disclaimer.
 *  
 *    Redistributions in binary form must reproduce the above copyright notice, this list of conditions and 
 *    the following disclaimer in the documentation and/or other materials provided with the distribution. 
 *    
 *    Neither the name of salesforce.com, inc. nor the names of its contributors may be used to endorse or 
 *    promote products derived from this software without specific prior written permission.
 *  
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED 
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A 
 * PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR 
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED 
 * TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) 
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING 
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE 
 * POSSIBILITY OF SUCH DAMAGE.
 */
package com.sforce.dataset.loader;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedWriter;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.MalformedInputException;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipParameters;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.input.BOMInputStream;
import org.supercsv.io.CsvListReader;
import org.supercsv.prefs.CsvPreference;

import com.sforce.async.AsyncApiException;
import com.sforce.async.BatchInfo;
import com.sforce.async.BatchStateEnum;
import com.sforce.async.BulkConnection;
import com.sforce.async.CSVReader;
import com.sforce.async.ContentType;
import com.sforce.async.JobInfo;
import com.sforce.async.JobStateEnum;
import com.sforce.async.OperationEnum;
import com.sforce.dataset.loader.file.schema.ExternalFileSchema;
import com.sforce.dataset.loader.file.schema.FieldType;
import com.sforce.dataset.loader.file.sort.CsvExternalSort;
import com.sforce.dataset.util.DatasetUtils;
import com.sforce.dataset.util.SfdcUtils;
import com.sforce.soap.partner.PartnerConnection;
import com.sforce.soap.partner.QueryResult;
import com.sforce.soap.partner.SaveResult;
import com.sforce.soap.partner.sobject.SObject;
import com.sforce.ws.ConnectionException;
import com.sforce.ws.ConnectorConfig;

public class DatasetLoader {
	

	private static final int DEFAULT_BUFFER_SIZE = 8*1024*1024;
	private static final int EOF = -1;
	private static final char LF = '\n';
	private static final char CR = '\r';
	private static final char QUOTE = '"';
	private static final char COMMA = ',';

	
	private static final String[] filePartsHdr = {"InsightsExternalDataId","PartNumber","DataFile"};
	
	private static final Pattern validChars = Pattern.compile("^[A-Za-z]+[A-Za-z\\d_]*$");

	public static final NumberFormat nf = NumberFormat.getIntegerInstance();
	private static int MAX_NUM_UPLOAD_THREADS = 3;


	@SuppressWarnings("deprecation")
	public static boolean uploadDataset(String inputFileString,
			String uploadFormat, CodingErrorAction codingErrorAction,
			Charset inputFileCharset, String datasetAlias,
			String datasetFolder,String datasetLabel, String Operation, boolean useBulkAPI,
			PartnerConnection partnerConnection)
	{
		File archiveDir = null;
		File datasetArchiveDir = null;
		File inputFile = null;
		boolean status = true;
		long digestTime = 0L;
		long uploadTime = 0L;
		boolean updateHdrJson = false;        
		//we only want a small capacity otherwise the reader thread will runaway
		BlockingQueue<String[]> q = new LinkedBlockingQueue<String[]>(10);  


		if(uploadFormat==null||uploadFormat.trim().isEmpty())
			uploadFormat = "binary";
		
		if(codingErrorAction==null)
			codingErrorAction = CodingErrorAction.REPORT;

		try {
			
			inputFile = new File(inputFileString);
			if(!inputFile.exists())
			{
				System.err.println("Error: File {"+inputFile.getAbsolutePath()+"} not found");
				return false;
			}
			
			ExternalFileSchema schema = null;
			System.out.println("\n*******************************************************************************");					
			if(FilenameUtils.getExtension(inputFile.getName()).equalsIgnoreCase("csv"))
			{			
				schema = ExternalFileSchema.init(inputFile, inputFileCharset);
				if(schema==null)
				{
					System.err.println("Failed to parse schema file {"+ ExternalFileSchema.getSchemaFile(inputFile) +"}");
					return false;
				}
			}else
			{
				schema = ExternalFileSchema.load(inputFile, inputFileCharset);
				if(schema==null)
				{
					System.err.println("Failed to load schema file {"+ ExternalFileSchema.getSchemaFile(inputFile) +"}");
					return false;
				}
			}
			System.out.println("*******************************************************************************\n");					


			if(datasetAlias==null||datasetAlias.trim().isEmpty())
			{
				//We only need to generate schema
				return true;
			}

			if(!validChars.matcher(datasetAlias).matches())
				throw new IllegalArgumentException("Invalid characters in datasetName {"+datasetAlias+"}");
			
			if(datasetAlias.length()>50)
				throw new IllegalArgumentException("datasetName {"+datasetAlias+"} should be less than 50 characters");
			

			//Validate access to the API before going any further
			if(!DatasetLoader.checkAPIAccess(partnerConnection))
			{
				System.err.println("Error: you do not have access to Analytics Cloud API. Please contact salesforce support");
				return false;
			}

			archiveDir = new File(inputFile.getParent(),"archive");
			try
			{
				FileUtils.forceMkdir(archiveDir);
			}catch(Throwable t)
			{
				t.printStackTrace();
			}
			
			datasetArchiveDir = new File(archiveDir,datasetAlias);
			try
			{
				FileUtils.forceMkdir(datasetArchiveDir);
			}catch(Throwable t)
			{
				t.printStackTrace();
			}
			
			//Insert header
			File metadataJsonFile = ExternalFileSchema.getSchemaFile(inputFile);
			if(metadataJsonFile == null || !metadataJsonFile.canRead())
			{
				System.err.println("Error: metadata Json file {"+metadataJsonFile+"} not found");		
				return false;
			}
			
			String hdrId = getLastIncompleteFileHdr(partnerConnection, datasetAlias);
			if(hdrId==null)
			{
				hdrId = insertFileHdr(partnerConnection, datasetAlias,datasetFolder, FileUtils.readFileToByteArray(metadataJsonFile), uploadFormat, Operation);
			}else
			{
				System.out.println("Record {"+hdrId+"} is being reused from InsightsExternalData");
				updateHdrJson = true;
			}
			if(hdrId ==null || hdrId.isEmpty())
			{
				System.err.println("Error: failed to insert header row into the saleforce SObject");		
				return false;
			}
			
			inputFile = CsvExternalSort.sortFile(inputFile, inputFileCharset, false, 1);
			
//Create the Bin file
//			File binFile = new File(csvFile.getParent(), datasetName + ".bin");
			File gzbinFile = inputFile;
			if(!FilenameUtils.getExtension(inputFile.getName()).equalsIgnoreCase("csv"))
			{
				if(!FilenameUtils.getExtension(inputFile.getName()).equalsIgnoreCase("gz") || !FilenameUtils.getExtension(inputFile.getName()).equalsIgnoreCase("zip"))
				{
					System.out.println("\n*******************************************************************************");					
					System.out.println("Input file does not have '.csv' extension. Assuming input file is 'ebin' format");
					System.out.println("*******************************************************************************\n");					
				}
			}
			
			File lastgzbinFile = new File(datasetArchiveDir, hdrId + "." + FilenameUtils.getBaseName(inputFile.getName()) + ".gz");
			if(!lastgzbinFile.exists())
			{
			if(uploadFormat.equalsIgnoreCase("binary") && FilenameUtils.getExtension(inputFile.getName()).equalsIgnoreCase("csv"))
			{	
				FileOutputStream fos = null;
				BufferedOutputStream out = null;
				BufferedOutputStream bos = null;
				GzipCompressorOutputStream gzos = null;
				try
				{
				gzbinFile = new File(inputFile.getParent(), hdrId + "." + FilenameUtils.getBaseName(inputFile.getName()) + ".gz");
				GzipParameters gzipParams = new GzipParameters();
				gzipParams.setFilename(FilenameUtils.getBaseName(inputFile.getName())  + ".bin");
				fos = new FileOutputStream(gzbinFile);
				bos = new BufferedOutputStream(fos,DEFAULT_BUFFER_SIZE);
				gzos = new GzipCompressorOutputStream(bos,gzipParams);
				out = new BufferedOutputStream(gzos,DEFAULT_BUFFER_SIZE);
				long totalRowCount = 0;
				long successRowCount = 0;
				long errorRowCount = 0;
				long startTime = System.currentTimeMillis();
				EbinFormatWriter w = new EbinFormatWriter(out, schema.objects.get(0).fields.toArray(new FieldType[0]));
				ErrorWriter ew = new ErrorWriter(inputFile,",");
				
				CsvListReader reader = new CsvListReader(new InputStreamReader(new BOMInputStream(new FileInputStream(inputFile), false), DatasetUtils.utf8Decoder(codingErrorAction , inputFileCharset )), CsvPreference.STANDARD_PREFERENCE);				
				WriterThread writer = new WriterThread(q, w, ew);
				Thread th = new Thread(writer,"Writer-Thread");
				th.setDaemon(true);
				th.start();
				
				try
				{
						@SuppressWarnings("unused")
						String[] header = reader.getHeader(true);		
						boolean hasmore = true;
						System.out.println("\n*******************************************************************************");					
						System.out.println("File: "+inputFile+", being digested to file: "+gzbinFile);
						System.out.println("*******************************************************************************\n");
						List<String> row = null;
						while (hasmore) 
						{
							try
							{
								row = reader.read();
								if(row!=null && !writer.isDone())
								{
									totalRowCount++;
									if(row.size()!=0 )
									{
										q.put(row.toArray(new String[row.size()]));
									}
								}else
								{
									hasmore = false;
								}
							}catch(Throwable t)
							{
//								if(errorRowCount==0)
//								{
//									System.err.println();
//								}
								System.err.println("Row {"+totalRowCount+"} has error {"+t+"}");
								if(t instanceof MalformedInputException)
								{
									while(!q.isEmpty())
									{
										try
										{
											Thread.sleep(1000);
										}catch(InterruptedException in)
										{
											in.printStackTrace();
										}
									}
									
									while(!writer.isDone())
									{
										q.put(new String[0]);
										try
										{
											Thread.sleep(1000);
										}catch(InterruptedException in)
										{
											in.printStackTrace();
										}
									}
									System.err.println("\n*******************************************************************************");
									System.err.println("The input file is not utf8 encoded. Please save it as UTF8 file first");
									System.err.println("*******************************************************************************\n");								
									status = false;
									hasmore = false;
								}
							}
						}//end while
						while(!q.isEmpty())
						{
							try
							{
								System.out.println("1 Waiting for writer to finish");
								Thread.sleep(1000);
							}catch(InterruptedException in)
							{
								in.printStackTrace();
							}
						}
						
						while(!writer.isDone())
						{
							q.put(new String[0]);
							try
							{
								System.out.println("2 Waiting for writer to finish");
								Thread.sleep(1000);
							}catch(InterruptedException in)
							{
								in.printStackTrace();
							}
						}
//					}
					successRowCount = w.getSuccessRowCount();
					errorRowCount = writer.getErrorRowCount();
				}finally
				{
					if(reader!=null)
						reader.close();
					if(w!=null)
						w.finish();
					if(ew!=null)
						ew.finish();
					if(out!=null)
						out.close();
					if(gzos!=null)
						gzos.close();
					if(bos!=null)
						bos.close();
					if(fos!=null)
						fos.close();
					out = null;
					gzos = null;
					bos = null;
					fos = null;
				}
				long endTime = System.currentTimeMillis();
				digestTime = endTime-startTime;
				if(!status)
					return status;
				
				if(successRowCount<1)
				{
					System.err.println("All rows failed. Please check {" + ew.getErrorFile() + "} for error rows");
					return false;
				}
				System.out.println("\n*******************************************************************************");									
				System.out.println("Total Rows: "+nf.format(totalRowCount)+", Success Rows: "+nf.format(successRowCount)+", Eror Rows: "+nf.format(errorRowCount));
				if(gzbinFile.length()>0)
					System.out.println("File: "+inputFile+", Size {"+nf.format(inputFile.length())+"} compressed to file: "+gzbinFile+", Size {"+nf.format(gzbinFile.length())+"} % Compression: "+(inputFile.length()/gzbinFile.length())*100 +"%"+", Digest Time {"+nf.format(digestTime) + "} msecs");
				System.out.println("*******************************************************************************\n");					
				} finally {
					if (out != null) {
						try {
							out.close();
							out = null;
						} catch (IOException e) {
						}
					}
					if (gzos != null) {
						try {
							gzos.close();
							gzos = null;
						} catch (IOException e) {
						}
					}
					if (bos != null) {
						try {
							bos.close();
							bos = null;
						} catch (IOException e) {
						}
					}
					if (fos != null) {
						try {
							fos.close();
							fos = null;
						} catch (IOException e) {
						}
					}
				}
			}else if(!FilenameUtils.getExtension(inputFile.getName()).equalsIgnoreCase("zip") && !FilenameUtils.getExtension(inputFile.getName()).equalsIgnoreCase("gz"))
			{
				BufferedInputStream fis = null;
				GzipCompressorOutputStream gzOut = null;
				long startTime = System.currentTimeMillis();
				try
				{
					gzbinFile = new File(inputFile.getParent(), FilenameUtils.getBaseName(hdrId + "." + inputFile.getName()) + ".gz");
					GzipParameters gzipParams = new GzipParameters();
					gzipParams.setFilename(inputFile.getName());
					gzOut = new GzipCompressorOutputStream(new DataOutputStream(new BufferedOutputStream(new FileOutputStream(gzbinFile))),gzipParams);
					fis = new BufferedInputStream(new FileInputStream(inputFile));  
					IOUtils.copy(fis, gzOut);
					long endTime = System.currentTimeMillis();
					if(gzbinFile.length()>0)
						System.out.println(" Input File, Size {"+nf.format(inputFile.length())+"} compressed to gz file, Size {"+nf.format(gzbinFile.length())+"} % Compression: "+(inputFile.length()/gzbinFile.length())*100 +"%"+", Compression Time {"+nf.format((endTime-startTime)) + "} msecs");			
				}finally
				{
					  if(gzOut!=null)
					  {
						  try {
							gzOut.close();
							gzOut=null;
						} catch (IOException e) {
						}
					  }
					  if(fis!=null)
					  {
						  try {
							fis.close();
							fis=null;
						} catch (IOException e) {
						}
					  }
				}
			}
			
			if(!gzbinFile.exists() || gzbinFile.length()<1)
			{
				System.err.println("Error: File {"+gzbinFile.getAbsolutePath()+"} not found or is zero bytes");
				return false;
			}else
			{
				if(!inputFile.equals(gzbinFile))
				{
					if(archiveDir.exists())
					{
						try
						{
							FileUtils.moveFile(gzbinFile, lastgzbinFile);
							gzbinFile = lastgzbinFile;
						}catch (Throwable t) {}
					}
				}
			}
			}else
			{
				System.out.println("Recovering process from last file {"+lastgzbinFile+"} upload");
				updateHdrJson = false; //The file is already digested, we cannot update the hdr now
				gzbinFile = lastgzbinFile;
			}
			
			long startTime = System.currentTimeMillis();
			status = uploadEM(gzbinFile, uploadFormat, metadataJsonFile, datasetAlias,datasetFolder, useBulkAPI, partnerConnection, hdrId, datasetArchiveDir, "Overwrite", updateHdrJson);
			long endTime = System.currentTimeMillis();
			uploadTime = endTime-startTime;

		} catch(MalformedInputException mie)
		{
			System.err.println("\n*******************************************************************************");
			System.err.println("The input file is not valid utf8 encoded. Please save it as UTF8 file first");
			mie.printStackTrace();
			status = false;
			System.err.println("*******************************************************************************\n");								
		} catch (Throwable t) {
			System.err.println("\n*******************************************************************************");					
			t.printStackTrace();
			status = false;
			System.err.println("*******************************************************************************\n");					
		}

		System.err.println("\n*****************************************************************************************************************");					
		if(status)			
			System.err.println("Succesfully uploaded {"+inputFile+"} to Dataset {"+datasetAlias+"} uploadTime {"+nf.format(uploadTime)+"} msecs" );
		else
			System.err.println("Failed to load {"+inputFile+"} to Dataset {"+datasetAlias+"}");
		System.err.println("*****************************************************************************************************************\n");					
		return status;
	}

	
	/**
	 * @param dataFile  The file to upload
	 * @param dataFormat The format of the file (CSV or Binary)
	 * @param metadataJson The metadata of the file
	 * @param datasetAlias The Alias of the dataset 
	 * @param datasetArchiveDir 
	 * @param username The Salesforce username
	 * @param password The Salesforce password
	 * @param token The Salesforce security token
	 * @param endpoint The Salesforce API endpoint URL 
	 * @return boolean status of the upload
	 * @throws Exception
	 */
	public static boolean uploadEM(File dataFile, String dataFormat, File metadataJson, String datasetAlias,String datasetFolder, boolean useBulk, PartnerConnection partnerConnection, String hdrId, File datasetArchiveDir, String Operation, boolean updateHdrJson) throws Exception 
	{
		byte[] metadataJsonBytes = null;
		if(metadataJson != null && metadataJson.canRead())
			metadataJsonBytes = FileUtils.readFileToByteArray(metadataJson);
		else
			System.err.println("warning: metadata Json file {"+metadataJson+"} not found");			

		return uploadEM(dataFile, dataFormat, metadataJsonBytes, datasetAlias, datasetFolder, useBulk, partnerConnection, hdrId, datasetArchiveDir, Operation, updateHdrJson);
	}

	/**
	 * @param dataFile  The file to upload
	 * @param dataFormat The format of the file (CSV or Binary)
	 * @param metadataJson The metadata of the file
	 * @param datasetAlias The Alias of the dataset 
	 * @param Operation 
	 * @param username The Salesforce username
	 * @param password The Salesforce password
	 * @param token The Salesforce security token
	 * @param endpoint The Salesforce API endpoint URL 
	 * @return boolean status of the upload
	 * @throws Exception
	 */
	public static boolean uploadEM(File dataFile, String dataFormat, byte[] metadataJsonBytes, String datasetAlias,String datasetFolder, boolean useBulk, PartnerConnection partnerConnection, String hdrId, File datasetArchiveDir, String Operation, boolean updateHdrJson) throws Exception 
	{
		BlockingQueue<Map<Integer, File>> q = new LinkedBlockingQueue<Map<Integer, File>>(); 
		LinkedList<Integer> existingFileParts = new LinkedList<Integer>();

		if(datasetAlias==null||datasetAlias.trim().isEmpty())
		{
			throw new IllegalArgumentException("datasetAlias cannot be blank");
		}
		
		DatasetLoader eu = new DatasetLoader();
		

		System.out.println("\n*******************************************************************************");					
		if(datasetFolder != null && datasetFolder.trim().length()!=0)
		{
			System.out.println("Uploading dataset {"+datasetAlias+"} to folder {" + datasetFolder + "}");
		}else
		{
			System.out.println("Uploading dataset {"+datasetAlias+"} to folder {" + partnerConnection.getUserInfo().getUserId() +"}");
		}
		System.out.println("*******************************************************************************\n");					

		if(hdrId==null || hdrId.trim().isEmpty())
		{
			hdrId = insertFileHdr(partnerConnection, datasetAlias,datasetFolder, metadataJsonBytes, dataFormat, Operation);
		}else
		{
			existingFileParts = getUploadedFileParts(partnerConnection, hdrId);
			if(updateHdrJson && existingFileParts.isEmpty())
				updateFileHdr(partnerConnection, hdrId, datasetAlias, datasetFolder, metadataJsonBytes, dataFormat, "None", Operation);
		}
		
		if(hdrId ==null || hdrId.isEmpty())
		{
			return false;
		}
			Map<Integer, File> fileParts = chunkBinary(dataFile, datasetArchiveDir);
			boolean allPartsUploaded = false;
			int retryCount=0; 
			int totalErrorCount = 0;
			if(fileParts.size()<MAX_NUM_UPLOAD_THREADS)
				MAX_NUM_UPLOAD_THREADS = 1; 
			while(retryCount<3)
			{
				LinkedList<FilePartsUploaderThread> upThreads = new LinkedList<FilePartsUploaderThread>();
				for(int i = 1;i<=MAX_NUM_UPLOAD_THREADS;i++)
				{
					FilePartsUploaderThread writer = new FilePartsUploaderThread(q, partnerConnection, hdrId);
					Thread th = new Thread(writer,"FilePartsUploaderThread-"+i);
					th.setDaemon(true);
					th.start();
					upThreads.add(writer);
				}

				if(useBulk)
				{
						if(eu.insertFilePartsBulk(partnerConnection, hdrId, createBatchZip(fileParts, hdrId), 0))
							return updateFileHdr(partnerConnection, hdrId, null, null, null, null, "Process", null);
						else
							return false;					
				}else
				{
					for(int i:fileParts.keySet())
					{
						if(!existingFileParts.contains(i))						
						{	
							HashMap<Integer, File> tmp = new HashMap<Integer, File>();
							tmp.put(i,fileParts.get(i));
							q.put(tmp);
						}
					}
					while(!q.isEmpty())
					{
						try
						{
							Thread.sleep(1000);
						}catch(InterruptedException in)
						{
							in.printStackTrace();
						}
					}
				}
				
				for(int i = 0;i<MAX_NUM_UPLOAD_THREADS;i++)
				{
					FilePartsUploaderThread uploader = upThreads.get(i);
					while(!uploader.isDone())
					{
						q.put(new HashMap<Integer, File>());
						try
						{
							Thread.sleep(1000);
						}catch(InterruptedException in)
						{
							in.printStackTrace();
						}
					}
					totalErrorCount = totalErrorCount + uploader.getErrorRowCount();
				}

				allPartsUploaded = true;
				existingFileParts = getUploadedFileParts(partnerConnection, hdrId);
				for(int i:fileParts.keySet())
				{
					if(!existingFileParts.contains(i))						
					{	
						allPartsUploaded = false;
					}else
					{
						FileUtils.deleteQuietly(fileParts.get(i));
					}
				}
				if(allPartsUploaded)
					break;
				retryCount++;
			}

				if(totalErrorCount==0 && allPartsUploaded)
				{
					return updateFileHdr(partnerConnection, hdrId, null, null, null, null, "Process", null);
				}else
				{
					System.err.println("Not all file parts were uploaded to InsightsExternalDataPart, remaining files:");
					for(int i:fileParts.keySet())
					{
						if(!existingFileParts.contains(i))						
						{	
							System.err.println(fileParts.get(i));
						}
					}
					return false;
				}
	}

	
	private static String insertFileHdr(PartnerConnection partnerConnection, String datasetAlias, String datasetContainer, byte[] metadataJson, String dataFormat, String Operation) throws Exception 
	{
		String rowId = null;
		long startTime = System.currentTimeMillis(); 
		try {

			SObject sobj = new SObject();	        
			sobj.setType("InsightsExternalData"); 
	        
	        if(dataFormat == null || dataFormat.equalsIgnoreCase("CSV"))
	        	sobj.setField("Format","CSV");
	        else
	        	sobj.setField("Format","Binary");
    		
	        sobj.setField("EdgemartAlias", datasetAlias);
	        
	        if(datasetContainer!=null && !datasetContainer.trim().isEmpty())
	        {
	        	sobj.setField("EdgemartContainer", datasetContainer); //Optional dataset folder name
	        }

	        //sobj.setField("IsIndependentParts",Boolean.FALSE); //Optional Defaults to false
    		
	        //sobj.setField("IsDependentOnLastUpload",Boolean.FALSE); //Optional Defaults to false
    		
    		if(metadataJson != null && metadataJson.length != 0)
    			sobj.setField("MetadataJson",metadataJson);
    		
    		if(Operation!=null)
    			sobj.setField("Operation",Operation);
    		else
    			sobj.setField("Operation","Overwrite");    			
    		
    		sobj.setField("Action","None");
    		
    		SaveResult[] results = partnerConnection.create(new SObject[] { sobj });    	
    		long endTime = System.currentTimeMillis(); 
    		for(SaveResult sv:results)
    		{ 	
    			if(sv.isSuccess())
    			{
    				rowId = sv.getId();
    				System.out.println("Record {"+ sv.getId() + "} Inserted into InsightsExternalData, upload time {"+nf.format(endTime-startTime)+"} msec");
    			}else
    			{
					System.err.println("Record {"+ sv.getId() + "} Insert Failed: " + (getErrorMessage(sv.getErrors())));
    			}
    		}

		} catch (ConnectionException e) {
			e.printStackTrace();
		}
		return rowId;
	}

	/*
	private boolean insertFileParts(PartnerConnection partnerConnection, String insightsExternalDataId, Map<Integer,File> fileParts, int retryCount) throws Exception 
	{
		LinkedHashMap<Integer,File> failedFileParts = new LinkedHashMap<Integer,File>();
		LinkedList<Integer> existingFileParts = getUploadedFileParts(partnerConnection, insightsExternalDataId);
		for(int i:fileParts.keySet())
		{
			if(existingFileParts.contains(i))
			{
				System.out.println("Skipping, File Part {"+ fileParts.get(i) + "}, already Inserted into InsightsExternalDataPart");
				fileParts.get(i).delete();
				continue;
			}
			try {
				long startTime = System.currentTimeMillis(); 
				SObject sobj = new SObject();
		        sobj.setType("InsightsExternalDataPart"); 
	    		sobj.setField("DataFile", FileUtils.readFileToByteArray(fileParts.get(i)));
	    		sobj.setField("InsightsExternalDataId", insightsExternalDataId);
	    		sobj.setField("PartNumber",i); //Part numbers should start at 1	    		
	    		SaveResult[] results = partnerConnection.create(new SObject[] { sobj });				    		
				long endTime = System.currentTimeMillis(); 
	    		for(SaveResult sv:results)
	    		{ 	
	    			if(sv.isSuccess())
	    			{
	    				System.out.println("File Part {"+ fileParts.get(i) + "} Inserted into InsightsExternalDataPart: " +sv.getId() + ", upload time {"+nf.format(endTime-startTime)+"} msec");
	    				try
	    				{
	    					fileParts.get(i).delete();
	    				}catch(Throwable t)
	    				{
	    					t.printStackTrace();
	    				}
	    			}else
	    			{
						System.err.println("File Part {"+ fileParts.get(i) + "} Insert Failed: " + (getErrorMessage(sv.getErrors())));
						failedFileParts.put(i, fileParts.get(i));
	    			}
	    		}
			} catch (Throwable t) {
				t.printStackTrace();
				System.err.println("File Part {"+ fileParts.get(i) + "} Insert Failed: " + t.toString());
				failedFileParts.put(i, fileParts.get(i));
			}
		}
		if(!failedFileParts.isEmpty())
		{
			if(retryCount<3)
			{		
				retryCount++;
				Thread.sleep(1000*retryCount);
//				partnerConnection = DatasetUtils.login(0, username, password, token, endpoint, sessionId);
				return insertFileParts(partnerConnection, insightsExternalDataId, failedFileParts, retryCount);
			}
			else
				return false;
		}
		return true;
	}
	*/

	private boolean insertFilePartsBulk(PartnerConnection partnerConnection, String insightsExternalDataId, Map<Integer,File> fileParts, int retryCount) throws Exception 
	{
        BulkConnection bulkConnection = getBulkConnection(partnerConnection.getConfig());
        JobInfo job = createJob("InsightsExternalDataPart", bulkConnection);
        LinkedHashMap<BatchInfo,File> batchInfoList = new LinkedHashMap<BatchInfo,File>();
		for(int i:fileParts.keySet())
		{
		        createBatch(fileParts.get(i), batchInfoList, bulkConnection, job);				
		}
        closeJob(bulkConnection, job.getId());
        awaitCompletion(bulkConnection, job, batchInfoList);
        checkResults(bulkConnection, job, batchInfoList);

		if(!batchInfoList.isEmpty())
		{
			if(retryCount<3)
			{		
				LinkedHashMap<Integer,File> failedFileParts = new LinkedHashMap<Integer,File>();
				for(BatchInfo b:batchInfoList.keySet())
				{
					File temp = batchInfoList.get(b);
					if(temp!=null && temp.exists())
					{
						for(int i:fileParts.keySet())
						{
							File tmp2 = fileParts.get(i);
							if(tmp2!=null && tmp2.exists())
							{
								if(tmp2.equals(temp))
								{
									failedFileParts.put(i, tmp2);
								}
							}
						}
					}
				}
				if(!failedFileParts.isEmpty())
				{
					retryCount++;
					Thread.sleep(1000*retryCount);
//					partnerConnection = DatasetUtils.login(0, username, password, token, endpoint, sessionId);
					return insertFilePartsBulk(partnerConnection, insightsExternalDataId, failedFileParts, retryCount);
				}else
				{
					return true;
				}
			}
			else
				return false;
		}
		return true;
	}
	
	
	private static boolean updateFileHdr(PartnerConnection partnerConnection, String rowId, String datasetAlias, String datasetContainer, byte[] metadataJson, String dataFormat, String Action, String Operation) throws Exception 
	{
		try {
								
			long startTime = System.currentTimeMillis(); 
			SObject sobj = new SObject();
	        sobj.setType("InsightsExternalData"); 
    		sobj.setId(rowId);
//	        sobj.setField("EdgemartAlias", datasetAlias);
    			        
	        if(dataFormat != null && !dataFormat.isEmpty())
	        {
	        	if(dataFormat.equalsIgnoreCase("CSV"))
	        		sobj.setField("Format","CSV");
	        	else if(dataFormat.equalsIgnoreCase("Binary"))
	        		sobj.setField("Format","Binary");
	        }
    		
	        if(datasetContainer!=null && !datasetContainer.trim().isEmpty())
	        {
	        	sobj.setField("EdgemartContainer", datasetContainer); //Optional dataset folder name
	        }

	        //sobj.setField("IsIndependentParts",Boolean.FALSE); //Optional Defaults to false
    		
	        //sobj.setField("IsDependentOnLastUpload",Boolean.FALSE); //Optional Defaults to false
    		
    		if(metadataJson != null && metadataJson.length != 0)
    			sobj.setField("MetadataJson",metadataJson);
    		
//    		"Overwrite"
    		if(Operation!=null && !Operation.isEmpty())
    		{
    			sobj.setField("Operation", Operation);
    		}

    		//Process, None
    		if(Action!=null  && !Action.isEmpty())
    		{
	    		sobj.setField("Action",Action);    		
    		}
    		
    		SaveResult[] results = partnerConnection.update(new SObject[] { sobj });				    		
			long endTime = System.currentTimeMillis(); 
    		for(SaveResult sv:results)
    		{ 	
    			if(sv.isSuccess())
    			{
    				rowId = sv.getId();
    				System.out.println("Record {"+ sv.getId() + "} updated in InsightsExternalData"+", upload time {"+nf.format(endTime-startTime)+"} msec");
    			}else
    			{
					System.err.println("Record {"+ sv.getId() + "} update Failed: " + (getErrorMessage(sv.getErrors())));
					return false;
    			}
    		}
    		return true;
		} catch (ConnectionException e) {
			e.printStackTrace();
		}
		return false;
	}
	
	/**
	 * @param inputFile
	 * @return
	 * @throws IOException
	 */
	public static Map<Integer,File> chunkBinary(File inputFile, File archiveDir) throws IOException 
	{	
		if(inputFile == null)
		{
			throw new IOException("chunkBinary() inputFile parameter is null");
		}
		if(!inputFile.canRead())
		{
			throw new IOException("chunkBinary() cannot read inputFile {"+inputFile+"}");
		}
		if(inputFile.length()==0)
		{
			throw new IOException("chunkBinary() inputFile {"+inputFile+"} is 0 bytes");
		}
		System.out.println("\n*******************************************************************************");					
		System.out.println("Chunking file {"+inputFile+"} into {"+nf.format(DEFAULT_BUFFER_SIZE)+"} size chunks\n");
		long startTime = System.currentTimeMillis();
		InputStream input = null;
		FileOutputStream tmpOut = null;
        LinkedHashMap<Integer,File> fileParts = new LinkedHashMap<Integer,File>();
		try 
		{
			input = new FileInputStream(inputFile);
			byte[] buffer = new byte[DEFAULT_BUFFER_SIZE];
            Arrays.fill(buffer, (byte)0);
			int n = 0;
			int count = -1;
			int filePartNumber = 0;
			while (EOF != (n = input.read(buffer))) {
				filePartNumber++;
	        	File tmpFile = new File(archiveDir,FilenameUtils.getBaseName(inputFile.getName())+"."+filePartNumber + "." + FilenameUtils.getExtension(inputFile.getName()));
				if(tmpFile != null && tmpFile.exists())
				{
					FileUtils.deleteQuietly(tmpFile);
					if(tmpFile.exists())
					{
						System.err.println("Failed to cleanup file {"+tmpFile+"}");
					}
				}
	            tmpOut = new FileOutputStream(tmpFile);			
	            tmpOut.write(buffer, 0, n);
	            Arrays.fill(buffer, (byte)0);
	            tmpOut.close();
	            tmpOut = null;
	            fileParts.put(Integer.valueOf(filePartNumber),tmpFile);
	            System.out.println("Creating File part {"+tmpFile+"}, size {"+nf.format(tmpFile.length())+"}");
				count = ((count == -1) ? n : (count + n));
			}
			if(count == -1)
			{
				throw new IOException("failed to chunkBinary file {"+inputFile+"}");
			}
		}finally {
			if (input != null)
				try {
					input.close();
				} catch (IOException e) {e.printStackTrace();}
			if (tmpOut != null)
				try {
					tmpOut.close();
				} catch (IOException e) {e.printStackTrace();}
		}
		long endTime = System.currentTimeMillis();
		System.out.println("\nChunked file {"+inputFile+"} into {"+fileParts.size()+"} chunks in {"+nf.format(endTime-startTime)+"} msecs");
		System.out.println("*******************************************************************************\n");					
		return fileParts;
	} 
	
	private static Map<Integer,File> createBatchZip(Map<Integer,File> fileParts,String insightsExternalDataId) throws IOException
	{
		LinkedHashMap<Integer,File> zipParts = new LinkedHashMap<Integer,File>();
		for(int i:fileParts.keySet())
		{
				File requestFile = new File(fileParts.get(i).getParent(),"request.txt");
				if(requestFile != null && requestFile.exists())
				{
					FileUtils.deleteQuietly(requestFile);
					if(requestFile.exists())
					{
						System.out.println("createBatchZip(): Failed to cleanup file {"+requestFile+"}");
					}
				}				
				String[] row = new String[3];
		        row[0] = insightsExternalDataId;
	    		row[1] = i+"";	    		
	    		row[2] = "#"+fileParts.get(i).getName();
	    		
	    		BufferedWriter fWriter = new BufferedWriter(new FileWriter(requestFile));
	    		boolean first = true;
				for(String val:filePartsHdr)
				{
					if(!first)
						fWriter.write(DatasetLoader.COMMA);
					first = false;
					fWriter.write(val);
				}
				fWriter.write("\n");

	    		
	    	    first = true;
				for(String val:row)
				{
					if(!first)
						fWriter.write(DatasetLoader.COMMA);
					first = false;
					fWriter.write(val);
				}
				fWriter.write("\n");
				fWriter.close();
				fWriter=null;
	    		
	    		File zipFile = new File(fileParts.get(i).getParent(), FilenameUtils.getBaseName(fileParts.get(i).getName()) + ".zip");
				if(zipFile != null && zipFile.exists())
				{
					FileUtils.deleteQuietly(zipFile);
					if(zipFile.exists())
					{
						System.out.println("createBatchZip(): Failed to cleanup file {"+zipFile+"}");
					}
				}				
				createZip(zipFile, new File[]{requestFile,fileParts.get(i)});
				zipParts.put(i,zipFile);
		}
		return zipParts;
	}
	
	/**
	 * This method will format the error message so that it can be logged by
	 * in the error csv file
	 * 
	 * @param errors
	 *            Array of com.sforce.soap.partner.Error[]
	 * @return formated Error String
	 */
	static String getErrorMessage(com.sforce.soap.partner.Error[] errors)
	{
		StringBuffer strBuf = new StringBuffer();
		for(com.sforce.soap.partner.Error err:errors)
		{
		      strBuf.append(" statusCode={");
		      strBuf.append(getCSVFriendlyString(com.sforce.ws.util.Verbose.toString(err.getStatusCode()))+"}");
		      strBuf.append(" message={");
		      strBuf.append(getCSVFriendlyString(com.sforce.ws.util.Verbose.toString(err.getMessage()))+"}");
		      if(err.getFields()!=null && err.getFields().length>0)
		      {
			      strBuf.append(" fields=");
			      strBuf.append(getCSVFriendlyString(com.sforce.ws.util.Verbose.toString(err.getFields())));
		      }
		}
		return strBuf.toString();
	}
	
	
	private static String getCSVFriendlyString(String content)
	{
		if(content!=null && !content.isEmpty())
		{
		content = replaceString(content, "" + COMMA, "");
		content = replaceString(content, "" + CR, "");
		content = replaceString(content, "" + LF, "");
		content = replaceString(content, "" + QUOTE, "");
		}
		return content;
	}
	
	
	private static String replaceString(String original, String pattern, String replace) 
	{
		if(original != null && !original.isEmpty() && pattern != null && !pattern.isEmpty() && replace !=null)
		{
			final int len = pattern.length();
			int found = original.indexOf(pattern);

			if (found > -1) {
				StringBuffer sb = new StringBuffer();
				int start = 0;

				while (found != -1) {
					sb.append(original.substring(start, found));
					sb.append(replace);
					start = found + len;
					found = original.indexOf(pattern, start);
				}

				sb.append(original.substring(start));

				return sb.toString();
			} else {
				return original;
			}
		}else
			return original;
	}
	
	
	   /**
     * Gets the results of the operation and checks for errors.
     */
    private static void checkResults(BulkConnection connection, JobInfo job,
    		LinkedHashMap<BatchInfo,File> batchInfoList)
            throws AsyncApiException, IOException {
    	@SuppressWarnings("unchecked")
    	LinkedHashMap<BatchInfo,File> tmp = (LinkedHashMap<BatchInfo, File>) batchInfoList.clone();
        for (BatchInfo b : tmp.keySet()) {
            CSVReader rdr =
              new CSVReader(connection.getBatchResultStream(job.getId(), b.getId()));
            List<String> resultHeader = rdr.nextRecord();
            int resultCols = resultHeader.size();

            List<String> row;
            while ((row = rdr.nextRecord()) != null) {
                Map<String, String> resultInfo = new LinkedHashMap<String, String>();
                for (int i = 0; i < resultCols; i++) {
                    resultInfo.put(resultHeader.get(i), row.get(i));
                }
                boolean success = Boolean.valueOf(resultInfo.get("Success"));
                boolean created = Boolean.valueOf(resultInfo.get("Created"));
                if (success && created) {
                    String id = resultInfo.get("Id");
//                    System.out.println("Created row with id " + id);
    				System.out.println("File Part {"+ batchInfoList.get(b) + "} Inserted into InsightsExternalDataPart: " +id);
    				File f = batchInfoList.remove(b);
    				try
    				{
    					if(f != null && f.exists())
    					{
    						f.delete();
	    					if(f.exists())
	    					{
	    						System.out.println("Failed to cleanup file {"+f+"}");
	    					}
    					}
    				}catch(Throwable t)
    				{
						System.out.println("Failed to cleanup file {"+f+"}");
    					t.printStackTrace();
    				}
                } else if (!success) {
                    String error = resultInfo.get("Error");
//                    System.out.println("Failed with error: " + error);
					System.err.println("File Part {"+ batchInfoList.get(b) + "} Insert Failed: " + error);
                }
            }
        }
    }



    private static void closeJob(BulkConnection connection, String jobId)
          throws AsyncApiException {
        JobInfo job = new JobInfo();
        job.setId(jobId);
        job.setState(JobStateEnum.Closed);
        connection.updateJob(job);
    }



    /**
     * Wait for a job to complete by polling the Bulk API.
     * 
     * @param connection
     *            BulkConnection used to check results.
     * @param job
     *            The job awaiting completion.
     * @param batchInfoList
     *            List of batches for this job.
     * @throws AsyncApiException
     */
    private static void awaitCompletion(BulkConnection connection, JobInfo job,
    		LinkedHashMap<BatchInfo,File> batchInfoList)
            throws AsyncApiException {
        long sleepTime = 0L;
        Set<String> incomplete = new LinkedHashSet<String>();
        for (BatchInfo bi : batchInfoList.keySet()) {
            incomplete.add(bi.getId());
        }
        while (!incomplete.isEmpty()) {
            try {
                Thread.sleep(sleepTime);
            } catch (InterruptedException e) {}
            System.out.println("Awaiting Async Batch results. Incomplete Batch Size {" + incomplete.size() + "}");
            sleepTime = 10000L;
            BatchInfo[] statusList =
              connection.getBatchInfoList(job.getId()).getBatchInfo();
            for (BatchInfo b : statusList) {
                if (b.getState() == BatchStateEnum.Completed
                  || b.getState() == BatchStateEnum.Failed) {
                    if (incomplete.remove(b.getId())) {
//                        System.out.println("BATCH STATUS:\n" + b);
                    }
                }
            }
        }
    }



    /**
     * Create a new job using the Bulk API.
     * 
     * @param sobjectType
     *            The object type being loaded, such as "Account"
     * @param connection
     *            BulkConnection used to create the new job.
     * @return The JobInfo for the new job.
     * @throws AsyncApiException
     */
    private static JobInfo createJob(String sobjectType, BulkConnection connection)
          throws AsyncApiException {
        JobInfo job = new JobInfo();
        job.setObject(sobjectType);
        job.setOperation(OperationEnum.insert);
        job.setContentType(ContentType.ZIP_CSV);
        job = connection.createJob(job);
//        System.out.println(job);
        return job;
    }

    

    /**
     * Create the BulkConnection used to call Bulk API operations.
     */
    private static BulkConnection getBulkConnection(ConnectorConfig partnerConfig)
          throws ConnectionException, AsyncApiException {
        ConnectorConfig config = new ConnectorConfig();
        config.setSessionId(partnerConfig.getSessionId());
        // The endpoint for the Bulk API service is the same as for the normal
        // SOAP uri until the /Soap/ part. From here it's '/async/versionNumber'
        String soapEndpoint = partnerConfig.getServiceEndpoint();
        String apiVersion = "31.0";
        String restEndpoint = soapEndpoint.substring(0, soapEndpoint.indexOf("Soap/"))
            + "async/" + apiVersion;
        config.setRestEndpoint(restEndpoint);
        // This should only be false when doing debugging.
        config.setCompression(true);
        // Set this to true to see HTTP requests and responses on stdout
        config.setTraceMessage(false);
        BulkConnection connection = new BulkConnection(config);
        return connection;
    }

    
    private void createBatch(File zipFile,
    		LinkedHashMap<BatchInfo,File> batchInfos, BulkConnection connection, JobInfo jobInfo)
    	              throws IOException, AsyncApiException {
    	        FileInputStream zipFileStream = new FileInputStream(zipFile);
    	        try {
    	    		System.out.println("creating bulk api batch for file {"+zipFile+"}");    	        	
    	        	BatchInfo batchInfo = connection.createBatchFromZipStream(jobInfo, zipFileStream);
//    	            System.out.println(batchInfo);
    	            batchInfos.put(batchInfo, zipFile);
    	        } finally {
    	            zipFileStream.close();
    	        }
    	    }


	private static void createZip(File zipfile,File[] files) throws IOException
	{
		if(zipfile == null)
		{
			throw new IOException("createZip(): called with null zipfile parameter");
		}
		if(files == null || files.length==0)
		{
			throw new IOException("createZip(): called with null files parameter");
		}
		System.out.println("creating batch request zip file {"+zipfile+"}");
		BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(zipfile));
		ZipOutputStream zip = new ZipOutputStream(bos);
		for(File file:files)
		{
			if(file == null)
			{
				throw new IOException("createZip(): called with null files parameter");
			}
			if(!file.exists())
			{
				throw new IOException("createZip(): called with file {"+file+"} that does not exist");
			}
			ZipEntry zipEntry = new ZipEntry(file.getName());
			zip.putNextEntry(zipEntry);
			FileInputStream origin = new FileInputStream(file);
			IOUtils.copy(origin, zip);
			zip.closeEntry();
			origin.close();
			origin = null;
		}
		zip.close();
		bos.close();
		zip = null;
		bos = null;
		for(File file:files)
		{
			try
			{
				if(file != null && file.exists())
				{
					file.delete();
					if(file.exists())
					{
						System.out.println("createZip(): Failed to cleanup file {"+file+"}");
					}
				}
			}catch(Throwable t)
			{
				System.out.println("createZip(): Failed to cleanup file {"+file+"}");
				t.printStackTrace();
			}
		}

	}


	/*
	public static boolean checkAPIAccess(String username2, String password2,
			String token2, String endpoint2, String sessionId2) {
		try {
			PartnerConnection partnerConnection = DatasetUtils.login(0, username2, password2, token2, endpoint2, sessionId2);
			Map<String,String> objectList = SfdcUtils.getObjectList(partnerConnection, Pattern.compile("\\b"+"InsightsExternalData"+"\\b"), false);
			if(objectList==null || objectList.size()==0)
			{
				System.err.println("\n");
				System.err.println("Error: Object {"+"InsightsExternalData"+"} not found");
				return false;
			}
			objectList = SfdcUtils.getObjectList(partnerConnection, Pattern.compile("\\b"+"InsightsExternalDataPart"+"\\b"), false);
			if(objectList==null || objectList.size()==0)
			{
				System.err.println("\n");
				System.err.println("Error: Object {"+"InsightsExternalDataPart"+"} not found");
				return false;
			}
			return true;
		} catch (ConnectionException e) {
			e.printStackTrace();
		}
		return false;
	}
	*/

	private static boolean checkAPIAccess(PartnerConnection partnerConnection) {
		try {
			Map<String,String> objectList = SfdcUtils.getObjectList(partnerConnection, Pattern.compile("\\b"+"InsightsExternalData"+"\\b"), false);
			if(objectList==null || objectList.size()==0)
			{
				System.err.println("\n");
				System.err.println("Error: Object {"+"InsightsExternalData"+"} not found");
				return false;
			}
			objectList = SfdcUtils.getObjectList(partnerConnection, Pattern.compile("\\b"+"InsightsExternalDataPart"+"\\b"), false);
			if(objectList==null || objectList.size()==0)
			{
				System.err.println("\n");
				System.err.println("Error: Object {"+"InsightsExternalDataPart"+"} not found");
				return false;
			}
			return true;
		} catch (ConnectionException e) {
			e.printStackTrace();
		}
		return false;
	}
	

	private static String getLastIncompleteFileHdr(PartnerConnection partnerConnection, String datasetAlias) throws Exception 
	{
		String rowId = null;
		String soqlQuery = String.format("SELECT Id FROM InsightsExternalData WHERE EdgemartAlias = '%s' AND Status = 'New' AND Action = 'None' ORDER BY CreatedDate DESC LIMIT 1",datasetAlias);
		partnerConnection.setQueryOptions(2000);
		QueryResult qr = partnerConnection.query(soqlQuery);
		int rowsSoFar = 0;
		boolean done = false;
		if (qr.getSize() > 0) 
		{
			while (!done) 
			{
				SObject[] records = qr.getRecords();
				for (int i = 0; i < records.length; ++i) 
				{
						String fieldName = "Id";
						Object value = SfdcUtils.getFieldValueFromQueryResult(fieldName,records[i]);
						if (value != null) {
							if(rowsSoFar==0) //only get the first one
								rowId = value.toString();
						}
					rowsSoFar++;
				}
				if (qr.isDone()) {
					done = true;
				} else {
					qr = partnerConnection.queryMore(qr.getQueryLocator());
				}
			}// End While
		}
		if(rowsSoFar>1)
		{
			System.err.println("getLastIncompleteFileHdr() returned more than one row");
		}
		return rowId; 
	}
	
	private static LinkedList<Integer> getUploadedFileParts(PartnerConnection partnerConnection, String hdrId) throws Exception 
	{
		LinkedList<Integer> existingPartList = new LinkedList<Integer>();
		String soqlQuery = String.format("SELECT Id,PartNumber FROM InsightsExternalDataPart WHERE InsightsExternalDataId = '%s' ORDER BY PartNumber ASC",hdrId);
		partnerConnection.setQueryOptions(2000);
		QueryResult qr = partnerConnection.query(soqlQuery);
		boolean done = false;
		if (qr.getSize() > 0) 
		{
			while (!done) 
			{
				SObject[] records = qr.getRecords();
				for (int i = 0; i < records.length; ++i) 
				{
						Object value = SfdcUtils.getFieldValueFromQueryResult("PartNumber",records[i]);
						if (value != null) {
							if(value instanceof Integer)
								existingPartList.add((Integer)value);
							else
								existingPartList.add(new Integer(value.toString()));
						}
				}
				if (qr.isDone()) {
					done = true;
				} else {
					qr = partnerConnection.queryMore(qr.getQueryLocator());
				}
			}// End While
		}
		return existingPartList; 
	}	

	 
}

