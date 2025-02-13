package io.metadew.iesi.script.execution;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.logging.log4j.Level;

import io.metadew.iesi.connection.tools.FolderTools;
import io.metadew.iesi.connection.tools.SQLTools;
import io.metadew.iesi.data.generation.execution.GenerationObjectExecution;
import io.metadew.iesi.framework.execution.FrameworkExecution;
import io.metadew.iesi.metadata.configuration.ConnectionParameterConfiguration;
import io.metadew.iesi.metadata.configuration.EnvironmentParameterConfiguration;
import io.metadew.iesi.metadata.configuration.IterationVariableConfiguration;
import io.metadew.iesi.metadata.configuration.RuntimeVariableConfiguration;
import io.metadew.iesi.metadata.definition.ComponentAttribute;
import io.metadew.iesi.metadata.definition.Iteration;
import io.metadew.iesi.metadata.definition.RuntimeVariable;
import io.metadew.iesi.runtime.definition.LookupResult;
import io.metadew.iesi.script.execution.data_instruction.DataInstruction;
import io.metadew.iesi.script.execution.data_instruction.DataInstructionRepository;
import io.metadew.iesi.script.operation.ActionParameterOperation;
import io.metadew.iesi.script.operation.DatasetOperation;
import io.metadew.iesi.script.operation.ImpersonationOperation;
import io.metadew.iesi.script.operation.IterationOperation;
import io.metadew.iesi.script.operation.RepositoryOperation;
import io.metadew.iesi.script.operation.StageOperation;

public class ExecutionRuntime {

	private FrameworkExecution frameworkExecution;

	private RuntimeVariableConfiguration runtimeVariableConfiguration;
	private IterationVariableConfiguration iterationVariableConfiguration;

	private String runId;
	private String runCacheFolderName;

	private Level level = Level.TRACE;

	private HashMap<String, StageOperation> stageOperationMap;
	private HashMap<String, RepositoryOperation> repositoryOperationMap;
	private HashMap<String, DatasetOperation> datasetOperationMap;
	private HashMap<String, IterationOperation> iterationOperationMap;

	private HashMap<String, ExecutionRuntimeExtension> executionRuntimeExtensionMap;

	private ImpersonationOperation impersonationOperation;

	private HashMap<String, DataInstruction> dataInstructions;

	public ExecutionRuntime() {

	}

	public ExecutionRuntime(FrameworkExecution frameworkExecution, String runId) {
		this.init(frameworkExecution, runId);
	}

	public void init(FrameworkExecution frameworkExecution, String runId) {
		this.setFrameworkExecution(frameworkExecution);
		this.setRunId(runId);

		// Create cache folder
		this.setRunCacheFolderName(this.getFrameworkExecution().getFrameworkConfiguration().getFolderConfiguration()
				.getFolderAbsolutePath("run.cache") + File.separator + this.getRunId());
		FolderTools.createFolder(this.getRunCacheFolderName());

		this.setRuntimeVariableConfiguration(
				new RuntimeVariableConfiguration(this.getFrameworkExecution(), this.getRunCacheFolderName()));
		this.setIterationVariableConfiguration(
				new IterationVariableConfiguration(this.getFrameworkExecution(), this.getRunCacheFolderName()));
		this.defineLoggingLevel();

		// Initialize maps
		this.setStageOperationMap(new HashMap<String, StageOperation>());
		this.setRepositoryOperationMap(new HashMap<String, RepositoryOperation>());
		this.setDatasetOperationMap(new HashMap<String, DatasetOperation>());
		this.setIterationOperationMap(new HashMap<String, IterationOperation>());
		this.setExecutionRuntimeExtensionMap(new HashMap<String, ExecutionRuntimeExtension>());

		// Initialize impersonations
		this.setImpersonationOperation(new ImpersonationOperation(this.getFrameworkExecution()));

		// Initialize extensions

		// Initialize data instructions
		dataInstructions = DataInstructionRepository.getReposistory(new GenerationObjectExecution(this.getFrameworkExecution()));
	}

	public void terminate() {
		// remove cache folder
		FolderTools.deleteFolder(this.getRunCacheFolderName(), true);

	}

	// Methods
	public void cleanRuntimeVariables() {
		this.getRuntimeVariableConfiguration().cleanRuntimeVariables(this.getRunId());
	}

	public void cleanRuntimeVariables(long processId) {
		this.getRuntimeVariableConfiguration().cleanRuntimeVariables(this.getRunId(), processId);
	}

	public void setRuntimeVariables(ResultSet rs) {
		if (SQLTools.getRowCount(rs) == 1) {
			try {
				ResultSetMetaData rsmd = rs.getMetaData();
				int numberOfColums = rsmd.getColumnCount();
				rs.beforeFirst();
				while (rs.next()) {
					for (int i = 1; i < numberOfColums + 1; i++) {
						this.setRuntimeVariable(rsmd.getColumnName(i), rs.getString(i));
					}
				}
				rs.close();
			} catch (SQLException e) {
				throw new RuntimeException("Error getting sql result " + e, e);
			}
		} else {
			throw new RuntimeException("Only 1 line of data expected");
		}
	}

	public void setRuntimeVariables(String input) {
		String[] lines = input.split("\n");
		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			int delim = line.indexOf("=");
			if (delim > 0) {
				String key = line.substring(0, delim);
				String value = line.substring(delim + 1);
				this.setRuntimeVariable(key, value);
			} else {
				// Not a valid configuration
			}
		}
	}

	public void setRuntimeVariablesFromList(ResultSet rs) {
		try {
			rs.beforeFirst();
			while (rs.next()) {
				this.setRuntimeVariable(rs.getString(1), rs.getString(2));
			}
			rs.close();
		} catch (SQLException e) {
			throw new RuntimeException("Error getting sql result " + e, e);
		}
	}

	public void setRuntimeVariable(String name, String value) {
		this.getFrameworkExecution().getFrameworkLog().log("exec.runvar.set=" + name + ":" + value, this.getLevel());
		this.getRuntimeVariableConfiguration().setRuntimeVariable(this.getRunId(), name, value);
	}

	public RuntimeVariable getRuntimeVariable(String name) {
		return this.getRuntimeVariableConfiguration().getRuntimeVariable(this.getRunId(), name);
	}

	public String getRuntimeVariableValue(String name) {
		return this.getRuntimeVariableConfiguration().getRuntimeVariableValue(this.getRunId(), name);
	}

	// Iteration Variables
	public void setIterationVariables(String listName, ResultSet rs) {
		this.getIterationVariableConfiguration().setIterationList(this.getRunId(), listName, rs);
	}

	// Load lists
	public void loadParamList(String input) {
		String[] parts = input.split(",");
		for (int i = 0; i < parts.length; i++) {
			String innerpart = parts[i];
			int delim = innerpart.indexOf("=");
			if (delim > 0) {
				String key = innerpart.substring(0, delim);
				String value = innerpart.substring(delim + 1);
				this.setRuntimeVariable(key, value);
			} else {
				// Not a valid configuration
			}
		}
	}

	public void loadParamFiles(String files) {
		String[] parts = files.split(",");
		for (int i = 0; i < parts.length; i++) {
			String innerpart = parts[i];
			this.loadParamFile(innerpart);
		}
	}

	public void loadParamFile(String file) {
		BufferedReader br;
		try {
			br = new BufferedReader(new FileReader(file));

			String line = null;
			while ((line = br.readLine()) != null) {
				String innerpart = line;
				int delim = innerpart.indexOf("=");
				if (delim > 0) {
					String key = innerpart.substring(0, delim);
					String value = innerpart.substring(delim + 1);
					this.setRuntimeVariable(key, value);
				} else {
					// Not a valid configuration
				}
			}
			br.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	// Resolution
	public String resolveVariables(String input) {
		// Prevent null issues during string operations
		if (input == null) {
			input = "";
		}
		String result = "";

		// First level: settings
		result = this.getFrameworkExecution().getFrameworkControl().resolveConfiguration(input);

		// Second level: runtime variables
		result = this.resolveRuntimeVariables(result);
		if (!input.equalsIgnoreCase(result))
			this.getFrameworkExecution().getFrameworkLog().log("exec.runvar.resolve=" + input + ":" + result,
					Level.TRACE);

		return result;
	}

	public String resolveVariables(ActionExecution actionExecution, String input) {
		// Prevent null issues during string operations
		if (input == null) {
			input = "";
		}
		String result = "";

		// First level: settings
		result = this.getFrameworkExecution().getFrameworkControl().resolveConfiguration(input);

		// Second: Action attributes
		result = this.resolveConfiguration(actionExecution, result);

		// third level: runtime variables
		result = this.resolveRuntimeVariables(result);
		if (!input.equalsIgnoreCase(result))
			this.getFrameworkExecution().getFrameworkLog().log("exec.runvar.resolve=" + input + ":" + result,
					Level.TRACE);

		return result;
	}

	public String resolveVariables(String input, boolean dup) {
		// Prevent null issues during string operations
		if (input == null) {
			input = "";
		}
		String result = "";

		// First level: settings
		result = this.getFrameworkExecution().getFrameworkControl().resolveConfiguration(input);

		// third level: runtime variables
		result = this.resolveRuntimeVariables(result);
		if (!input.equalsIgnoreCase(result))
			this.getFrameworkExecution().getFrameworkLog().log("exec.runvar.resolve=" + input + ":" + result,
					Level.TRACE);

		return result;
	}


	private String resolveRuntimeVariables(String input) {
		int openPos;
		int closePos;
		String variable_char = "#";
		String midBit;
		String replaceValue;
		String temp = input;
		while (temp.indexOf(variable_char) > 0 || temp.startsWith(variable_char)) {
			openPos = temp.indexOf(variable_char);
			closePos = temp.indexOf(variable_char, openPos + 1);
			midBit = temp.substring(openPos + 1, closePos);

			// Replace
			replaceValue = this.getRuntimeVariableValue(midBit);
			if (replaceValue != null) {
				input = input.replaceAll(variable_char + midBit + variable_char, replaceValue);
			}
			temp = temp.substring(closePos + 1, temp.length());

		}
		return input;
	}

	public String resolveActionTypeVariables(String input,
			HashMap<String, ActionParameterOperation> actionParameterOperationMap) {
		int openPos;
		int closePos;
		String variable_char_open = "[";
		String variable_char_close = "]";
		String midBit;
		String replaceValue;
		String temp = input;
		while (temp.indexOf(variable_char_open) > 0 || temp.startsWith(variable_char_open)) {
			openPos = temp.indexOf(variable_char_open);
			closePos = temp.indexOf(variable_char_close, openPos + 1);
			midBit = temp.substring(openPos + 1, closePos);

			// Replace
			replaceValue = actionParameterOperationMap.get(midBit).getValue();
			if (replaceValue != null) {
				input = input.replace(variable_char_open + midBit + variable_char_close, replaceValue);
			}
			temp = temp.substring(closePos + 1, temp.length());

		}
		return input;
	}

	public String resolveMapVariables(String input, HashMap<String, String> variableMap) {
		int openPos;
		int closePos;
		String variable_char_open = "[";
		String variable_char_close = "]";
		String midBit;
		String replaceValue;
		String temp = input;
		while (temp.indexOf(variable_char_open) > 0 || temp.startsWith(variable_char_open)) {
			openPos = temp.indexOf(variable_char_open);
			closePos = temp.indexOf(variable_char_close, openPos + 1);
			midBit = temp.substring(openPos + 1, closePos);

			// Replace
			replaceValue = variableMap.get(midBit);
			if (replaceValue != null) {
				input = input.replace(variable_char_open + midBit + variable_char_close, replaceValue);
			}
			temp = temp.substring(closePos + 1, temp.length());

		}
		return input;
	}

	public String resolveComponentTypeVariables(String input, List<ComponentAttribute> componentAttributeList,
			String environment) {
		HashMap<String, ComponentAttribute> componentAttributeMap = this
				.getComponentAttributeHashmap(componentAttributeList, environment);
		int openPos;
		int closePos;
		String variable_char_open = "[";
		String variable_char_close = "]";
		String midBit;
		String replaceValue;
		String temp = input;
		while (temp.indexOf(variable_char_open) > 0 || temp.startsWith(variable_char_open)) {
			openPos = temp.indexOf(variable_char_open);
			closePos = temp.indexOf(variable_char_close, openPos + 1);
			midBit = temp.substring(openPos + 1, closePos);

			// Replace
			replaceValue = componentAttributeMap.get(midBit).getValue();
			if (replaceValue != null) {
				input = input.replace(variable_char_open + midBit + variable_char_close, replaceValue);
			}
			temp = temp.substring(closePos + 1, temp.length());

		}
		return input;
	}

	private HashMap<String, ComponentAttribute> getComponentAttributeHashmap(
			List<ComponentAttribute> componentAttributeList, String environment) {
		if (componentAttributeList == null) {
			return null;
		}

		HashMap<String, ComponentAttribute> componentAttributeMap = new HashMap<String, ComponentAttribute>();
		for (ComponentAttribute componentAttribute : componentAttributeList) {
			if (componentAttribute.getEnvironment().trim().equalsIgnoreCase(environment)) {
				componentAttributeMap.put(componentAttribute.getName(), componentAttribute);
			}
		}
		return componentAttributeMap;
	}

	public String resolveConfiguration(ActionExecution actionExecution, String input) {
		int openPos;
		int closePos;
		String variable_char = "#";
		String midBit;
		String replaceValue = null;
		String temp = input;
		while (temp.indexOf(variable_char) > 0 || temp.startsWith(variable_char)) {
			openPos = temp.indexOf(variable_char);
			closePos = temp.indexOf(variable_char, openPos + 1);
			midBit = temp.substring(openPos + 1, closePos);

			// Try to find a configuration value
			// If none is found, null is set by default
			try {
				replaceValue = actionExecution.getComponentAttributeOperation().getProperty(midBit);
			} catch (Exception e) {
				replaceValue = null;
			}

			// Replacing the value if found
			if (replaceValue != null) {
				input = input.replaceAll(variable_char + midBit + variable_char, replaceValue);
			}
			temp = temp.substring(closePos + 1, temp.length());

		}
		return input;
	}

	// Get cross concept lookup
	public LookupResult resolveConceptLookup(ExecutionControl executionControl, String input, boolean dup) {
		LookupResult lookupResult = new LookupResult();
		int openPos;
		int closePos;
		String variable_char = "{{";
		String variable_char_close = "}}";
		String midBit;
		String replaceValue;
		String temp = input;
		while (temp.indexOf(variable_char) > 0 || temp.startsWith(variable_char)) {
			List<String> items = new ArrayList<>();
			String tempInstructions = temp;
			while (tempInstructions.indexOf(variable_char) > 0 || tempInstructions.startsWith(variable_char)) {
				openPos = tempInstructions.indexOf(variable_char);
				closePos = tempInstructions.indexOf(variable_char_close);
				midBit = tempInstructions.substring(openPos + 2, closePos).trim();
				items.add(midBit);
				tempInstructions = midBit;
			}

			// get last value
			String instruction = items.get(items.size() - 1);

			// check split different types
			String instructionType = instruction.substring(0, 1).toLowerCase();
			String instructionOutput = instruction;

			// Lookup
			if (instructionType.equals("=")) {
				int lookupOpenPos = instruction.indexOf("(");
				int lookupClosePos = instruction.indexOf(")", lookupOpenPos + 1);
				String lookupContext = instruction.substring(1, lookupOpenPos).trim().toLowerCase();
				String lookupScope = instruction.substring(lookupOpenPos + 1, lookupClosePos).trim();
				if (lookupContext.equals("connection") || lookupContext.equals("conn")) {
					instructionOutput = this.lookupConnectionInstruction(executionControl, lookupScope);
				} else if (lookupContext.equals("environment") || lookupContext.equals("env")) {
					instructionOutput = this.lookupEnvironmentInstruction(executionControl, lookupScope);
				} else if (lookupContext.equals("dataset") || lookupContext.equals("ds")) {
					instructionOutput = this.lookupDatasetInstruction(executionControl, lookupScope);
				} else if (lookupContext.equals("file") || lookupContext.equals("f")) {
					instructionOutput = this.lookupFileInstruction(executionControl, lookupScope);
				}  else if (lookupContext.equals("coalesce") || lookupContext.equals("ifnull") || lookupContext.equals("nvl")) {
					instructionOutput = this.lookupCoalesceResult(executionControl, lookupScope);
				}
				// Generate data
			} else if (instructionType.equals("*")) {
				int lookupOpenPos = instruction.indexOf("(");
				int lookupClosePos = instruction.indexOf(")", lookupOpenPos + 1);
				String lookupContext = instruction.substring(1, lookupOpenPos).trim().toLowerCase();
				String lookupScope = instruction.substring(lookupOpenPos + 1, lookupClosePos).trim();
				instructionOutput = this.generateDataInstruction(executionControl, lookupContext, lookupScope);
				// run scripts
			} else if (instructionType.equals("!")) {
				int lookupOpenPos = instruction.indexOf("(");
				int lookupClosePos = instruction.lastIndexOf(")");
				String lookupContext = instruction.substring(1, lookupOpenPos).trim().toLowerCase();
				String lookupScope = instruction.substring(lookupOpenPos + 1, lookupClosePos).trim();
				lookupResult.setContext(lookupContext);
				if (lookupScope.startsWith("\"")) lookupScope = lookupScope.substring(1);
				if (lookupScope.endsWith("\"")) lookupScope = lookupScope.substring(0, lookupScope.length() - 1);
				instructionOutput = lookupScope;
				// Verify for javascript / js and jexl / jxl
			}
			replaceValue = instructionOutput;
			// this.decrypt(variable_char + midBit + variable_char_close);
			if (replaceValue != null) {
				input = input.replace(variable_char + instruction + variable_char_close, replaceValue);
			}
			temp = input;
		}
		
		lookupResult.setValue(input);
		return lookupResult;

	}

	
	private String lookupConnectionInstruction(ExecutionControl executionControl, String input) {
		String output = input;

		// Parse input
		String[] parts = input.split(",");
		String connectionName = parts[0];
		String connectionParameterName = parts[1];

		ConnectionParameterConfiguration connectionParameterConfiguration = new ConnectionParameterConfiguration(
				this.getFrameworkExecution());
		String connectionParameterValue = connectionParameterConfiguration.getConnectionParameterValue(connectionName,
				executionControl.getEnvName(), connectionParameterName);

		if (connectionParameterValue != null) {
			output = connectionParameterValue;
		}

		return output;
	}

	private String lookupEnvironmentInstruction(ExecutionControl executionControl, String input) {
		String output = input;

		// Parse input
		String[] parts = input.split(",");
		String environmentName = parts[0];
		String environmentParameterName = parts[1];

		EnvironmentParameterConfiguration environmentParameterConfiguration = new EnvironmentParameterConfiguration(
				this.getFrameworkExecution());
		String environmentParameterValue = environmentParameterConfiguration
				.getEnvironmentParameterValue(environmentName, environmentParameterName);

		if (environmentParameterValue != null) {
			output = environmentParameterValue;
		}

		return output;
	}

	private String lookupDatasetInstruction(ExecutionControl executionControl, String input) {
		String output = input;

		// Parse input
		String[] parts = input.split(",");
		String datasetName = parts[0];
		String datasetItem = parts[1];

		DatasetOperation datasetOperation = executionControl.getExecutionRuntime().getDatasetOperation(datasetName);

		if (datasetItem != null && !datasetItem.equals("")) {
			output = datasetOperation.getDataItem(datasetItem);
		}

		return output;
	}

	private String lookupFileInstruction(ExecutionControl executionControl, String input) {
		String output = input;
		output = SQLTools.getFirstSQLStmt(input);
		return output;
	}

	private String lookupCoalesceResult(ExecutionControl executionControl, String input) {
		String output = "";
		String[] parts = input.split(",");
		for (int i = 0; i < parts.length; i++) {
			String temp = parts[i].trim();
			if (!temp.isEmpty()) {
				output = parts[i];
				break;
			}
		}
		return output;
	}

	private String generateDataInstruction(ExecutionControl executionControl, String context, String input)
	{
		DataInstruction dataInstruction = dataInstructions.get(context);
		if (dataInstruction == null)
		{
			throw new IllegalArgumentException(MessageFormat.format("No data instruction named {0} found.", context));
		}
		else
		{
			return dataInstruction.generateOutput(input);
		}
	}

	// Conversion
	public InputStream convertToInputStream(File file) {
		String output = "";
		try {
			@SuppressWarnings("resource")
			BufferedReader bufferedReader = new BufferedReader(new FileReader(file));
			String readLine = "";
			while ((readLine = bufferedReader.readLine()) != null) {
				output += this.resolveVariables(readLine);
				output += "\n";
			}
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException("The system cannot find the path specified", e);
		}
		return new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8));
	}

	// Define logging level
	private void defineLoggingLevel() {
		if (this.getFrameworkExecution().getFrameworkControl()
				.getProperty(this.getFrameworkExecution().getFrameworkConfiguration().getSettingConfiguration()
						.getSettingPath("commandline.display.runtime.variable"))
				.equals("Y")) {
			this.setLevel(Level.INFO);
		} else {
			this.setLevel(Level.TRACE);
		}
	}

	// Stage Management
	public void setStage(String stageName) {
		StageOperation stageOperation = new StageOperation(this.getFrameworkExecution(), stageName);
		this.getStageOperationMap().put(stageName, stageOperation);
	}

	public void setOperation(String stageName, StageOperation stageOperation) {
		this.getStageOperationMap().put(stageName, stageOperation);
	}

	public StageOperation getOperation(String stageName) {
		return this.getStageOperationMap().get(stageName);
	}

	// Repository Management
	public void setRepository(ExecutionControl executionControl, String repositoryReferenceName, String repositoryName, String repositoryInstanceName, String repositoryInstanceLabels) {
		RepositoryOperation repositoryOperation = new RepositoryOperation(this.getFrameworkExecution(), executionControl, repositoryName,
				repositoryInstanceName, repositoryInstanceLabels);
		this.getRepositoryOperationMap().put(repositoryReferenceName, repositoryOperation);
	}

	// Dataset Management
	public void setDataset(String datasetName, String datasetLabels) {
		DatasetOperation datasetOperation = new DatasetOperation(this.getFrameworkExecution(), datasetName,
				datasetLabels);
		this.getDatasetOperationMap().put(datasetName, datasetOperation);
	}

	public void setDatasetOperation(String datasetName, DatasetOperation datasetOperation) {
		this.getDatasetOperationMap().put(datasetName, datasetOperation);
	}

	public DatasetOperation getDatasetOperation(String datasetName) {
		return this.getDatasetOperationMap().get(datasetName);
	}

	// Iteration Management
	public void setIteration(Iteration iteration) {
		IterationOperation iterationOperation = new IterationOperation(iteration);
		this.getIterationOperationMap().put(iteration.getName(), iterationOperation);
	}

	public void setIterationOperation(IterationOperation iterationOperation) {
		this.getIterationOperationMap().put(iterationOperation.getIteration().getName(), iterationOperation);
	}

	public IterationOperation getIterationOperation(String iterationName) {
		return this.getIterationOperationMap().get(iterationName);
	}

	// Execution Runtime Extension Management
	public void setExecutionRuntimeExtension(ExecutionRuntimeExtension executionRuntimeExtension) {
		this.getExecutionRuntimeExtensionMap().put(executionRuntimeExtension.getExecutionRuntimeExtensionName(),
				executionRuntimeExtension);
	}

	public ExecutionRuntimeExtension getExecutionRuntimeExtension(String executionRuntimeExtensionName) {
		return this.getExecutionRuntimeExtensionMap().get(executionRuntimeExtensionName);
	}

	public boolean executionRuntimeExtensionExists(String executionRuntimeExtensionName) {
		ExecutionRuntimeExtension executionRuntimeExtension = this.getExecutionRuntimeExtensionMap()
				.get(executionRuntimeExtensionName);
		if (executionRuntimeExtension != null) {
			// Value exists
			return true;
		} else {
			// Check if only the key exists
			if (this.getExecutionRuntimeExtensionMap().containsKey(executionRuntimeExtensionName)) {
				// Only key exists with null value
				return true;
			} else {
				// No key and no value exist
				return false;
			}
		}
	}

	// Impersonations
	public void setImpersonationName(String impersonationName) {
		this.getImpersonationOperation().setImpersonation(impersonationName);
	}

	public void setImpersonationCustom(String impersonationCustom) {
		this.getImpersonationOperation().setImpersonationCustom(impersonationCustom);
	}

	// Getters and Setters
	public FrameworkExecution getFrameworkExecution() {
		return frameworkExecution;
	}

	public void setFrameworkExecution(FrameworkExecution frameworkExecution) {
		this.frameworkExecution = frameworkExecution;
	}

	public RuntimeVariableConfiguration getRuntimeVariableConfiguration() {
		return runtimeVariableConfiguration;
	}

	public void setRuntimeVariableConfiguration(RuntimeVariableConfiguration runtimeVariableConfiguration) {
		this.runtimeVariableConfiguration = runtimeVariableConfiguration;
	}

	public String getRunId() {
		return runId;
	}

	public void setRunId(String runId) {
		this.runId = runId;
	}

	public Level getLevel() {
		return level;
	}

	public void setLevel(Level level) {
		this.level = level;
	}

	public HashMap<String, StageOperation> getStageOperationMap() {
		return stageOperationMap;
	}

	public void setStageOperationMap(HashMap<String, StageOperation> stageOperationMap) {
		this.stageOperationMap = stageOperationMap;
	}

	public ImpersonationOperation getImpersonationOperation() {
		return impersonationOperation;
	}

	public void setImpersonationOperation(ImpersonationOperation impersonationOperation) {
		this.impersonationOperation = impersonationOperation;
	}

	public HashMap<String, DatasetOperation> getDatasetOperationMap() {
		return datasetOperationMap;
	}

	public void setDatasetOperationMap(HashMap<String, DatasetOperation> datasetOperationMap) {
		this.datasetOperationMap = datasetOperationMap;
	}

	public HashMap<String, ExecutionRuntimeExtension> getExecutionRuntimeExtensionMap() {
		return executionRuntimeExtensionMap;
	}

	public void setExecutionRuntimeExtensionMap(
			HashMap<String, ExecutionRuntimeExtension> executionRuntimeExtensionMap) {
		this.executionRuntimeExtensionMap = executionRuntimeExtensionMap;
	}

	public String getRunCacheFolderName() {
		return runCacheFolderName;
	}

	public void setRunCacheFolderName(String runCacheFolderName) {
		this.runCacheFolderName = runCacheFolderName;
	}

	public HashMap<String, IterationOperation> getIterationOperationMap() {
		return iterationOperationMap;
	}

	public void setIterationOperationMap(HashMap<String, IterationOperation> iterationOperationMap) {
		this.iterationOperationMap = iterationOperationMap;
	}

	public IterationVariableConfiguration getIterationVariableConfiguration() {
		return iterationVariableConfiguration;
	}

	public void setIterationVariableConfiguration(IterationVariableConfiguration iterationVariableConfiguration) {
		this.iterationVariableConfiguration = iterationVariableConfiguration;
	}

	public HashMap<String, RepositoryOperation> getRepositoryOperationMap() {
		return repositoryOperationMap;
	}

	public void setRepositoryOperationMap(HashMap<String, RepositoryOperation> repositoryOperationMap) {
		this.repositoryOperationMap = repositoryOperationMap;
	}

}
