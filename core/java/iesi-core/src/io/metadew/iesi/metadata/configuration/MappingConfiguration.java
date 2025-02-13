package io.metadew.iesi.metadata.configuration;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.metadew.iesi.framework.execution.FrameworkExecution;
import io.metadew.iesi.metadata.definition.Mapping;
import io.metadew.iesi.metadata.operation.DataObjectOperation;
import io.metadew.iesi.metadata.operation.TypeConfigurationOperation;

public class MappingConfiguration {

	private Mapping mapping;
	private FrameworkExecution frameworkExecution;
	private String dataObjectType = "Mapping";

	// Constructors
	public MappingConfiguration(Mapping mapping, FrameworkExecution frameworkExecution) {
		this.setMapping(mapping);
		this.setFrameworkExecution(frameworkExecution);
	}

	public MappingConfiguration(FrameworkExecution frameworkExecution) {
		this.setFrameworkExecution(frameworkExecution);
	}

	public Mapping getMapping(String mappingName) {
		String conf = TypeConfigurationOperation.getMappingConfigurationFile(this.getFrameworkExecution(),
				this.getDataObjectType(), mappingName);
		DataObjectOperation dataObjectOperation = new DataObjectOperation(this.getFrameworkExecution(), conf);
		ObjectMapper objectMapper = new ObjectMapper();
		return objectMapper.convertValue(dataObjectOperation.getDataObject().getData(),
				Mapping.class);
	}

	// Getters and Setters
	public FrameworkExecution getFrameworkExecution() {
		return frameworkExecution;
	}

	public void setFrameworkExecution(FrameworkExecution frameworkExecution) {
		this.frameworkExecution = frameworkExecution;
	}

	public String getDataObjectType() {
		return dataObjectType;
	}

	public void setDataObjectType(String dataObjectType) {
		this.dataObjectType = dataObjectType;
	}

	public Mapping getMapping() {
		return mapping;
	}

	public void setMapping(Mapping mapping) {
		this.mapping = mapping;
	}

}