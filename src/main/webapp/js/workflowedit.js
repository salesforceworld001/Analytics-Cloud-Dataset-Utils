$(document).ready(function() {	

	$('.input-group input[required], .input-group textarea[required], .input-group select[required]').on('keyup change', function() {
		var $form = $(this).closest('form'),
            $group = $(this).closest('.input-group'),
			$addon = $group.find('.input-group-addon'),
			$icon = $addon.find('span'),
			$cont = $(this).closest('.container'),
			state = false;
            
    	if (!$group.data('validate')) {
			state = $(this).val() ? true : false;
		}else if ($group.data('validate') == "email") {
			state = /^([a-zA-Z0-9_\.\-])+\@(([a-zA-Z0-9\-])+\.)+([a-zA-Z0-9]{2,4})+$/.test($(this).val())
		}else if($group.data('validate') == 'phone') {
			state = /^[(]{0,1}[0-9]{3}[)]{0,1}[-\s\.]{0,1}[0-9]{3}[-\s\.]{0,1}[0-9]{4}$/.test($(this).val())
		}else if ($group.data('validate') == "length") {
			state = $(this).val().length >= $group.data('length') ? true : false;
		}else if ($group.data('validate') == "number") {
			state = !isNaN(parseFloat($(this).val())) && isFinite($(this).val());
		}

		if (state) {
				$addon.removeClass('danger');
				$addon.addClass('success');
				$icon.attr('class', 'glyphicon glyphicon-ok');
		}else{
				$addon.removeClass('success');
				$addon.addClass('danger');
				$icon.attr('class', 'glyphicon glyphicon-remove');
		}
        
        if ($cont.find('.input-group-addon.danger').length == 0) {
            $('#submit-xmd-btn').prop('disabled', false);
            $('#submit-xmd-btn').removeClass('disabled')
        }else{
            $('#submit-xmd-btn').prop('disabled', true);
            $('#submit-xmd-btn').addClass('disabled');
        }
	});
    
    $('.input-group input[required], .input-group textarea[required], .input-group select[required]').trigger('change');
 	
	var container = $('#jsoncontainer')[0];
	var options = {
		mode: 'code',
		modes: ['code', 'tree']
	};
	editor = new JSONEditor(container, options);

	var dataflowAlias = decodeURIComponent(urlParam('dataflowAlias'));
	var create = decodeURIComponent(urlParam('create'));
	var dataflowId = decodeURIComponent(urlParam('dataflowId'));
	
	if (create == undefined || isEmpty(create) )
	{
		create = false;
	}else
	{
		if(create == 'true')
			create = true;
		else
			create = false;
	}
		
	if (dataflowAlias == undefined || isEmpty(dataflowAlias) ) 
	{
		if(create)
		{
			$("#dataflowLabel").prop("disabled", false);
			$("#dataflowLabel").change();
		}else
		{
			self.location.href = 'dataflows.html';
		}
	}else
	{
		$("#dataflowAlias").val(dataflowAlias);
//		$("#dataflowLabel").change();
//		$("#dataflowAlias").prop("disabled", true);

		if (dataflowAlias == 'AnalyticsCloudReplicationDataflow' ) 
		{
			$('#sync-replication-btn').show();
            $('#sync-replication-btn').prop('disabled', false);
            $('#sync-replication-btn').removeClass('disabled')
		}else
		{
			$('#sync-replication-btn').hide();		
            $('#sync-replication-btn').prop('disabled', true);
            $('#sync-replication-btn').addClass('disabled')
		}
		
		getJson(editor,dataflowAlias,dataflowId);	
	}

	$("button[name=postjson]").click(sendJson);

	$("button[name=syncjson]").click(syncJson);

	function sendJson(event){
		var json_string;

		try{
			json_string = JSON.stringify(editor.get());
		}
		catch(err){
			alert("Not a valid JSON!");
			return;
		}

		dataflowAlias = $("#dataflowAlias").val();
		dataflowLabel = $("#dataflowLabel").val();
		if(isEmpty(dataflowLabel))
		{
			alert("You must enter a valid data flow Name!");
			return;
		}

		
//		$("#dataflowLabel").prop("disabled", true);		 
		$('#submit-xmd-btn').button('loading');
		editor.setMode('view');

//		dataflowAlias = $("#dataflowAlias").val();
				
		setTimeout(function() {
			$.ajax({
			    url: '/json',
			    type: 'POST',
			    data: {	
			    			jsonString: json_string,
			    			type:'dataflow',
			    			dataflowAlias:dataflowAlias,
			    			dataflowLabel: dataflowLabel,
			    			dataflowId: dataflowId,
			    			create: create
			     		},
			    contentType: 'application/x-www-form-urlencoded; charset=utf-8',
			    dataType: 'json',
			    async: false,
			    success: function() {
					$('#submit-xmd-btn').button('reset');
					editor.setMode('code');
			    },
	            error: function(jqXHR, status, error) {
					$('#submit-xmd-btn').button('reset');
					editor.setMode('code');
	               if (isEmpty(jqXHR.responseText) || jqXHR.responseText.indexOf("<!DOCTYPE HTML>") > -1) {
	                   self.location.href = 'login.html';
	               }
	               else
	               {
			        	   handleError($("#title2").get(0),jqXHR.responseText);
	               }
	          	 }
			});
		},60);
		
	}

});

$(document).ajaxSend(function(event, request, settings) {
	  $("#title2").empty();
	  $('#loading-indicator').show();
});

$(document).ajaxComplete(function(event, request, settings) {
	$('#loading-indicator').hide();
});

function isEmpty(str) {
    return (!str || 0 === str.length || str === 'null');
}

function urlParam(name){
    var results = new RegExp('[\?&]' + name + '=([^&#]*)').exec(window.location.href);
    if (results==null){
       return null;
    }
    else{
       return results[1] || 0;
    }
}

function getJson(editor,dataflowAlias,dataflowId){
		full_url = "/json?type=dataflow&dataflowAlias=" + dataflowAlias + "&dataflowId=" + dataflowId;
		$.getJSON(full_url, {}, function(data){
			$("#dataflowLabel").val(data.masterLabel);
			$("#dataflowLabel").change();
	    	   if(data.workflowType === 'Local')
	    	   {
	    		   $("#dataflowLabel").prop("disabled", false);
	    	   }else
	    	   {
	    		   $("#dataflowLabel").prop("disabled", true);	    		   
	    	   }
			editor.set(data.workflowDefinition);
		})
        .fail(function(jqXHR, textStatus, errorThrown) { 
            if (isEmpty(jqXHR.responseText) || jqXHR.responseText.indexOf("<!DOCTYPE HTML>") > -1) {
                self.location.href = 'login.html';
            }else
            {
			    handleError($("#title2").get(0),jqXHR.responseText);
            }
        });
}


	function syncJson(event){	
		var dataflowAlias = $("#dataflowAlias").val();		
		$('#sync-replication-btn').button('loading');
	
		full_url = "/json?syncDataflow=true&type=dataflow&dataflowAlias=" + dataflowAlias;
		$.getJSON(full_url, {}, function(data){
			$('#sync-replication-btn').button('reset');		
//			var container = $('#jsoncontainer')[0];
//			var options = {
//				mode: 'code',
//				modes: ['code', 'tree']
//			};
//			var editor = new JSONEditor(container, options);
			editor.set(data.workflowDefinition);			
		})
        .fail(function(jqXHR, textStatus, errorThrown) { 
			$('#sync-replication-btn').button('reset');		
            if (isEmpty(jqXHR.responseText) || jqXHR.responseText.indexOf("<!DOCTYPE HTML>") > -1) {
                self.location.href = 'login.html';
            }else
            {
			        	   handleError($("#title2").get(0),jqXHR.responseText);
            }
        });
}