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
package com.sforce.dataset.loader.file.schema;

import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.regex.Pattern;

import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;

import com.fasterxml.jackson.annotation.JsonIgnore;

public class FieldType {
	
	public static final int STRING = 1;
	public static final int MEASURE = 2;
	public static final int DATE = 3;
	public static final int max_precision = 18;
	
	private static final String TEXT_TYPE = "Text";
	private static final String NUMERIC_TYPE = "Numeric";
	private static final String DATE_TYPE = "Date";
	private static final Pattern validChars = Pattern.compile("^[A-Za-z]+[A-Za-z\\d_]*$");
	
	private transient int fType = 0;
	private transient int measure_multiplier = 0;

//	private String name; //Required
//	private String fullyQualifiedName; //Optional
//	private String label; //Optional
//	private String description; //Optional
//	private String type; //Required - Text, Numeric, Date
//	private String defaultValue; //required for numeric types
//	private int precision; //Required if fType is Numeric, the number 256.99 has a precision of 5
//	private int scale; //Required if fType is Numeric, the number 256.99 has a scale of 2 
//	private String format; //Required if fType is Numeric or Date
//	private boolean isUniqueId = false; //Optional
//	private boolean isMultiValue = false; //Optional
//	private String multivalueSeperator = null; //Required if isMultiValue = true
//	private String acl; //Optional
//	private boolean isAclField; //Optional	
	
	
	//private long Id; //(generated by workflow when the source is instantiated)
	//private String Alias; //(generated by workflow)


	public String name = null; //Required
	public String fullyQualifiedName = null; //Required
	public String label = null; //Required
	public String description = null; //Optional
	public String type = null; //Required - Text, Numeric, Date
	public int precision = 0; //Required if type is Numeric, the number 256.99 has a precision of 5
	public int scale = 0; //Required if type is Numeric, the number 256.99 has a scale of 2
	public String defaultValue = null; //required for numeric types	
	public String format = null; //Required if type is Numeric or Date
//	public boolean isNillable = true; //Optional
	public boolean isSystemField = false; //Optional
	public boolean isUniqueId = false; //Optional
	public boolean isMultiValue = false; //Optional 
	public String multiValueSeparator = null; //Optional - only used if IsMultiValue = true separator
	//public boolean isFilterable = true; //Optional
	//public String parentObjectFullyQualifiedName; //Optional keep track of parent object where the field came from in case of views)
	//private long parentObjectId; //(generated by workflow)
	//public String relatedObjectFullyQualifiedName; //Optional - FKey
	//private long relatedObjectId; //(generated by workflow)
	//public String relatedFieldFullyQualifiedName; //Optional FKey
	//private long relatedFieldId; //Optional generated by workflow
	//public String parentFieldFullyQualifiedName; //Optional Hierarchy  in the same object
	//private long parentFieldId; //Optional generated by workflow
	//public String acl = null; //Optional
	//public boolean isAclField = false; //Optional
	public int fiscalMonthOffset = 0;
	public int firstDayOfWeek = 1; //1=SUNDAY, 2=MONDAY etc..
	public boolean canTruncateValue = true; //Optional 
	public boolean isSkipped = false; //Optional 
	public String decimalSeparator = ".";
	public int sortIndex = 0; //Index start at 1, 0 means not to sort
	public boolean isSortAscending = true; //Optional  if index > 0 then will sort ascending if true
	
	public boolean isComputedField = false; //Optional  if this field is computed
	public String computedFieldExpression = null; //Optional the expression to compute this field	
	private transient CompiledScript compiledScript = null;
	private transient SimpleDateFormat compiledDateFormat = null;
	private transient Date defaultDate = null;
	

	public static FieldType GetStringKeyDataType(String name, String multivalueSeperator, String defaultValue)
	{
		FieldType kdt = new FieldType(name);
		kdt.fType = (FieldType.STRING);
		kdt.setType(FieldType.TEXT_TYPE);
		if(multivalueSeperator!=null && multivalueSeperator.length()!=0)
		{
			kdt.multiValueSeparator = multivalueSeperator;
			kdt.isMultiValue = true;
		}
		if(defaultValue!=null)
			kdt.setDefaultValue(defaultValue);
		else
			kdt.setDefaultValue("");
		kdt.setLabel(name);
		kdt.setFullyQualifiedName(kdt.name);
		kdt.setDescription(name);
		return kdt;
	}
	
	@Override
	public String toString() {
		return "FieldType [name=" + name + ",label=" + label + ", type=" + type
				+ ", defaultValue=" + defaultValue + ", scale=" + scale
				+ ", format=" + format + "]";
	}
	
	public static FieldType GetMeasureKeyDataType(String name,int precision,int scale, Long defaultValue)
	{
		FieldType kdt = new FieldType(name);
		kdt.fType = (FieldType.MEASURE);
		kdt.setType(FieldType.NUMERIC_TYPE);
		if(precision<=0 || precision>max_precision)
			precision = max_precision;
		kdt.setPrecision(precision);
		if(scale<=0)
			scale = 0;
    	//If scale greater than precision, then force the scale to be 1 less than precision
        if (scale > precision)
        	scale = (precision > 1) ? (precision - 1) : 0;
        	
		kdt.setScale(scale);
		kdt.measure_multiplier = (int) Math.pow(10, scale);
		kdt.setDefaultValue(BigDecimal.valueOf(defaultValue).toPlainString());
		kdt.setLabel(name);
		kdt.setFullyQualifiedName(kdt.name);
		kdt.setDescription(name);
		return kdt;
	}

	
	/**
	 * @param name the field name
	 * @param format refer to Date format section in https://developer.salesforce.com/docs/atlas.en-us.bi_dev_guide_ext_data.meta/bi_dev_guide_ext_data/bi_ext_data_schema_reference.htm
	 * @param defaultValue
	 * @return
	 */
	public static FieldType GetDateKeyDataType(String name, String format, String defaultValue)
	{
		FieldType kdt = new FieldType(name);
		kdt.fType = (FieldType.DATE);
		kdt.setType(FieldType.DATE_TYPE);
		new SimpleDateFormat(format);
		kdt.setFormat(format);
		if(defaultValue!=null)
			kdt.setDefaultValue(defaultValue);
		else
			kdt.setDefaultValue("");
		kdt.setLabel(name);
		kdt.setFullyQualifiedName(kdt.name);
		kdt.setDescription(name);
		return kdt;
	}

	public FieldType() {
		super();
	}

	FieldType(String name) {
		if(name==null||name.isEmpty())
		{
			throw new IllegalArgumentException("field name is null {"+name+"}");			
		}
		if(name.length()>255)
		{
			throw new IllegalArgumentException("field name cannot be greater than 255 characters");			
		}
//		name = ExternalFileSchema.replaceSpecialCharacters(name);
		if(!validChars.matcher(name).matches())
			throw new IllegalArgumentException("Invalid characters in field name {"+name+"}");
		this.name = name;
	}

	@JsonIgnore
	public int getMeasure_multiplier() {
		if(type.equals(FieldType.NUMERIC_TYPE) && measure_multiplier==0)
		{
			if(precision<=0)
				precision = max_precision;
			if(scale<=0)
				scale = 0;
	    	//If scale greater than precision, then force the scale to be 1 less than precision
	        if (scale > precision)
	        	scale = (precision > 1) ? (precision - 1) : 0;
				
	    	measure_multiplier = (int) Math.pow(10, scale);
		}
		return measure_multiplier;
	}

	@JsonIgnore
	public int getfType() {
		if(fType==0)
		{
			if(type.equals(FieldType.DATE_TYPE))
				fType = FieldType.DATE;
			else if(type.equals(FieldType.NUMERIC_TYPE))
				fType = FieldType.MEASURE;
			else
				fType = FieldType.STRING;
		}
		return fType;
	}
	
	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public int getPrecision() {
		return precision;
	}

	public void setPrecision(int precision) {
		this.precision = precision;
	}

	public int getScale() {
		return scale;
	}

	public void setScale(int scale) {
		this.scale = scale;
	}

	public String getFormat() {
		return format;
	}

	public void setFormat(String format) {
		if(this.type.equals(FieldType.DATE_TYPE) && format != null)
		{
			compiledDateFormat = new SimpleDateFormat(format);
			compiledDateFormat.setTimeZone(TimeZone.getTimeZone("GMT")); //All dates must be in GMT
			if(this.defaultValue!=null && !this.defaultValue.isEmpty())
			{
				try {
					this.defaultDate = compiledDateFormat.parse(this.defaultValue);
				} catch (ParseException e) {
					throw new IllegalArgumentException(e.toString());
				}
			}
		}
		this.format = format;
	}
	
	@JsonIgnore
	public SimpleDateFormat getCompiledDateFormat() {
		return compiledDateFormat;
	}

	@JsonIgnore
	public Date getDefaultDate() {
		return defaultDate;
	}

	public String getMultiValueSeparator() {
		return multiValueSeparator;
	}

	public void setMultiValueSeparator(String multiValueSeparator) {
		this.multiValueSeparator = multiValueSeparator;
	}

	public String getDefaultValue() {
		return defaultValue;
	}

	public void setDefaultValue(String defaultValue) {
		if(this.type.equals(FieldType.DATE_TYPE) && compiledDateFormat != null)
		{
			if(defaultValue!=null && !defaultValue.isEmpty())
			{
				try {
					this.defaultDate = compiledDateFormat.parse(defaultValue);
				} catch (ParseException e) {
					throw new IllegalArgumentException(e.toString());
				}
			}
		}
		this.defaultValue = defaultValue;
	}

	public String getFullyQualifiedName() {
		return fullyQualifiedName;
	}

	public void setFullyQualifiedName(String fullyQualifiedName) {
		this.fullyQualifiedName = fullyQualifiedName;
	}

	public String getLabel() {
		return label;
	}

	public void setLabel(String label) {
		this.label = label;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	/*
	@JsonIgnore
	public boolean isNillable() {
		return isNillable;
	}

	@JsonIgnore
	public void setNillable(boolean isNillable) {
		this.isNillable = isNillable;
	}
	*/
	
	@JsonIgnore
	public boolean isSystemField() {
		return isSystemField;
	}

	@JsonIgnore
	public void setSystemField(boolean isSystemField) {
		this.isSystemField = isSystemField;
	}

	@JsonIgnore
	public boolean isUniqueId() {
		return isUniqueId;
	}

	@JsonIgnore
	public void setUniqueId(boolean isUniqueId) {
		this.isUniqueId = isUniqueId;
	}

	@JsonIgnore
	public boolean isMultiValue() {
		return isMultiValue;
	}

	@JsonIgnore
	public void setMultiValue(boolean isMultiValue) {
		this.isMultiValue = isMultiValue;
	}

//	public String getAcl() {
//		return acl;
//	}
//
//	public void setAcl(String acl) {
//		this.acl = acl;
//	}

	/*
	@JsonIgnore
	public boolean isAclField() {
		return isAclField;
	}

	@JsonIgnore
	public void setAclField(boolean isAclField) {
		this.isAclField = isAclField;
	}
	*/
	
	public int getFiscalMonthOffset() {
		return fiscalMonthOffset;
	}
	
	public void setFiscalMonthOffset(int fiscalMonthOffset) {
		this.fiscalMonthOffset = fiscalMonthOffset;
	}

	public String getComputedFieldExpression() {
		return computedFieldExpression;
	}

	public void setComputedFieldExpression(String computedFieldExpression) {
		if(computedFieldExpression != null && computedFieldExpression.length()!=0)
		{
	        try
	        {
			    ScriptEngineManager mgr = new ScriptEngineManager();
		        ScriptEngine jsEngine = mgr.getEngineByName("JavaScript");
		        if (jsEngine instanceof Compilable)
	            {
	                Compilable compEngine = (Compilable)jsEngine;
	                this.compiledScript = compEngine.compile(computedFieldExpression);
	        		this.computedFieldExpression = computedFieldExpression;
	            }
	        } catch(Throwable t)
	        {
	        	throw new IllegalArgumentException(t.toString());
	        }
		}
	}

	@JsonIgnore
	public CompiledScript getCompiledScript() {
		return compiledScript;
	}
	

	@Override
	public int hashCode() {
		final int prime = 31;
		int result = 1;
//		result = prime * result + ((acl == null) ? 0 : acl.hashCode());
		result = prime * result
				+ ((defaultValue == null) ? 0 : defaultValue.hashCode());
		result = prime * result
				+ ((description == null) ? 0 : description.hashCode());
		result = prime * result + fiscalMonthOffset;
		result = prime * result + ((format == null) ? 0 : format.hashCode());
		result = prime
				* result
				+ ((fullyQualifiedName == null) ? 0 : fullyQualifiedName
						.hashCode());
		result = prime * result + (isMultiValue ? 1231 : 1237);
		result = prime * result + (isSystemField ? 1231 : 1237);
		result = prime * result + (isUniqueId ? 1231 : 1237);
		result = prime * result + ((label == null) ? 0 : label.hashCode());
		result = prime
				* result
				+ ((multiValueSeparator == null) ? 0 : multiValueSeparator
						.hashCode());
		result = prime * result + ((name == null) ? 0 : name.hashCode());
		result = prime * result + precision;
		result = prime * result + scale;
		result = prime * result + ((type == null) ? 0 : type.hashCode());
		return result;
	}

	@Override
	public boolean equals(Object obj) {
		if (this == obj) {
			return true;
		}
		if (obj == null) {
			return false;
		}
		if (getClass() != obj.getClass()) {
			return false;
		}
		FieldType other = (FieldType) obj;
//		if (acl == null) {
//			if (other.acl != null) {
//				return false;
//			}
//		} else if (!acl.equals(other.acl)) {
//			return false;
//		}
		if (defaultValue == null) {
			if (other.defaultValue != null) {
				return false;
			}
		} else if (!defaultValue.equals(other.defaultValue)) {
			return false;
		}
		if (description == null) {
			if (other.description != null) {
				return false;
			}
		} else if (!description.equals(other.description)) {
			return false;
		}
		if (fiscalMonthOffset != other.fiscalMonthOffset) {
			return false;
		}
		if (format == null) {
			if (other.format != null) {
				return false;
			}
		} else if (!format.equals(other.format)) {
			return false;
		}
		if (fullyQualifiedName == null) {
			if (other.fullyQualifiedName != null) {
				return false;
			}
		} else if (!fullyQualifiedName.equals(other.fullyQualifiedName)) {
			return false;
		}
		if (isMultiValue != other.isMultiValue) {
			return false;
		}
		if (isSystemField != other.isSystemField) {
			return false;
		}
		if (isUniqueId != other.isUniqueId) {
			return false;
		}
		if (label == null) {
			if (other.label != null) {
				return false;
			}
		} else if (!label.equals(other.label)) {
			return false;
		}
		if (multiValueSeparator == null) {
			if (other.multiValueSeparator != null) {
				return false;
			}
		} else if (!multiValueSeparator.equals(other.multiValueSeparator)) {
			return false;
		}
		if (name == null) {
			if (other.name != null) {
				return false;
			}
		} else if (!name.equals(other.name)) {
			return false;
		}
		if (precision != other.precision) {
			return false;
		}
		if (scale != other.scale) {
			return false;
		}
		if (type == null) {
			if (other.type != null) {
				return false;
			}
		} else if (!type.equals(other.type)) {
			return false;
		}
		return true;
	}

}
