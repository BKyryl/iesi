package io.metadew.iesi.script.execution;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

import org.apache.logging.log4j.Level;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.metadew.iesi.common.text.TextTools;
import io.metadew.iesi.connection.tools.SQLTools;
import io.metadew.iesi.framework.configuration.FrameworkStatus;
import io.metadew.iesi.framework.execution.FrameworkExecution;
import io.metadew.iesi.metadata.backup.BackupExecution;
import io.metadew.iesi.metadata.definition.ScriptLog;
import io.metadew.iesi.metadata.restore.RestoreExecution;

public class ExecutionControl
{

	private FrameworkExecution frameworkExecution;

	private ExecutionRuntime executionRuntime;

	private ExecutionLog executionLog;

	private ExecutionTrace executionTrace;

	private ScriptLog scriptLog;

	private String runId;

	private String envName;

	private long processId;

	private List<Long> processIdList;

	private boolean actionErrorStop = false;
	private boolean scriptExit = false;

	// Constructors
	public ExecutionControl(FrameworkExecution frameworkExecution) {
		this.setFrameworkExecution(frameworkExecution);
		this.setExecutionLog(new ExecutionLog(this.getFrameworkExecution()));
		this.setExecutionTrace(new ExecutionTrace(this.getFrameworkExecution()));
		this.initializeRootScript();
	}

	// Methods
	@SuppressWarnings({"unchecked", "rawtypes"})
	private void initializeRootScript()
	{
		// Generate unique run id
		this.setRunId(this.getFrameworkExecution().getFrameworkLog().getUuid().toString());
		// Create execution runtime
		this.initializeExecutionRuntime(this.getFrameworkExecution(), this.getRunId());

		// Prepare process identifier enablers
		this.setProcessIdList(new ArrayList());
		this.getProcessIdList().add(-1L);
	}

	@SuppressWarnings({ "rawtypes", "unchecked" })
	private void initializeExecutionRuntime(FrameworkExecution frameworkExecution, String runId) {
		String customExecutionRuntime = frameworkExecution.getFrameworkControl().getProperty(frameworkExecution
				.getFrameworkConfiguration().getSettingConfiguration().getSettingPath("script.execution.runtime"));
		if (customExecutionRuntime.trim().equals("")) {
			this.setExecutionRuntime(new ExecutionRuntime(frameworkExecution, runId));
		} else {
			try {
				Class classRef = Class.forName(customExecutionRuntime);
				Object instance = classRef.newInstance();

				Class initParams[] = { FrameworkExecution.class, String.class };
				Method init = classRef.getDeclaredMethod("init", initParams);
				Object[] initArgs = { this.getFrameworkExecution(), runId };
				init.invoke(instance, initArgs);

				ObjectMapper objectMapper = new ObjectMapper();
				this.setExecutionRuntime(objectMapper.convertValue(instance, ExecutionRuntime.class));
			}
			catch (Exception e)
			{
				e.printStackTrace();
			}

		}

	}

	public void setEnvironment(String environmentName)
	{
		this.setEnvName(environmentName);

		// Set environment variables
		this.getExecutionRuntime().setRuntimeVariablesFromList(this.getFrameworkExecution().getMetadataControl()
				.getConnectivityRepositoryConfiguration()
				.executeQuery("select env_par_nm, env_par_val from "
						+ this.getFrameworkExecution().getMetadataControl().getConnectivityRepositoryConfiguration()
								.getMetadataTableConfiguration().getTableName("EnvironmentParameters")
						+ " where env_nm = '" + this.getEnvName() + "' order by env_par_nm asc, env_par_val asc"));
	}

	public void terminate()
	{
		this.getExecutionRuntime().terminate();
	}

	// Log start
	public void logStart(ScriptExecution scriptExecution, ScriptExecution parentScriptExecution)
	{
		Long parentProcessId = -1L;
		if (scriptExecution.isRootScript())
		{
			// Reset Process Id
			this.resetProcessId();
			// Set parent Process Id
			parentProcessId = 0L;
			// Initialize runtime variables
			this.getExecutionRuntime().setRuntimeVariablesFromList(this.getFrameworkExecution().getMetadataControl()
					.getConnectivityRepositoryConfiguration()
					.executeQuery("select env_par_nm, env_par_val from "
							+ this.getFrameworkExecution().getMetadataControl().getConnectivityRepositoryConfiguration()
									.getMetadataTableConfiguration().getTableName("EnvironmentParameters")
							+ " where env_nm = '" + this.getEnvName() + "' order by env_par_nm asc, env_par_val asc"));
		} else if (scriptExecution.isRouteScript()) {
			// Set parent Process Id
			parentProcessId = scriptExecution.getParentScriptExecution().getProcessId();
		} else {
			// Set parent Process Id
			parentProcessId = parentScriptExecution.getProcessId();
		}
		// Generate Process Id
		this.getNewProcessId();

		String query = "INSERT INTO "
				+ this.getFrameworkExecution().getMetadataControl().getResultRepositoryConfiguration().getMetadataTableConfiguration()
						.getTableName("ScriptResults")
				+ " (RUN_ID, PRC_ID, PARENT_PRC_ID, SCRIPT_ID, SCRIPT_VRS_NB, ENV_NM, ST_NM, STRT_TMS, END_TMS)";
		query += " VALUES ";
		query += "(";
		query += SQLTools.GetStringForSQL(this.getRunId());
		query += ",";
		query += SQLTools.GetStringForSQL(this.getProcessId());
		query += ",";
		query += SQLTools.GetStringForSQL(parentProcessId);
		query += ",";
		query += SQLTools.GetStringForSQL(scriptExecution.getScript().getId());
		query += ",";
		query += SQLTools.GetStringForSQL(scriptExecution.getScript().getVersion().getNumber());
		query += ",";
		query += SQLTools.GetStringForSQL(this.getEnvName());
		query += ",";
		query += SQLTools.GetStringForSQL("ACTIVE");
		query += ",";
		query += this.getFrameworkExecution().getMetadataControl().getResultRepositoryConfiguration()
				.getSystemTimestampExpression();
		query += ",";
		query += "null";
		query += ")";
		query += ";";

		InputStream inputStream = new ByteArrayInputStream(query.getBytes(StandardCharsets.UTF_8));
		this.getFrameworkExecution().getMetadataControl().getResultRepositoryConfiguration().executeScript(inputStream);

		this.setScriptLog(new ScriptLog());
		this.getScriptLog().setRun(this.getRunId());
		this.getScriptLog().setProcess(this.getProcessId());
		this.getScriptLog().setParent(parentProcessId);
		this.getScriptLog().setIdentifier(scriptExecution.getScript().getId());
		this.getScriptLog().setVersion(scriptExecution.getScript().getVersion().getNumber());
		this.getScriptLog().setEnvironment(this.getEnvName());

		Timestamp startTimestamp = new Timestamp(System.currentTimeMillis());
		this.getScriptLog().setStart(startTimestamp);
		this.getExecutionLog().setLog(this.getScriptLog());
	}

	public void logStart(ActionExecution actionExecution)
	{
		// Generate Process Id
		this.getNewProcessId();

		String query = "INSERT INTO "
				+ this.getFrameworkExecution().getMetadataControl().getResultRepositoryConfiguration().getMetadataTableConfiguration()
						.getTableName("ActionResults")
				+ " (RUN_ID, PRC_ID, ACTION_ID, ENV_NM, ST_NM, STRT_TMS, END_TMS)";
		query += " VALUES ";
		query += "(";
		query += SQLTools.GetStringForSQL(this.getRunId());
		query += ",";
		query += SQLTools.GetStringForSQL(this.getProcessId());
		query += ",";
		query += SQLTools.GetStringForSQL(actionExecution.getAction().getId());
		query += ",";
		query += SQLTools.GetStringForSQL(this.getEnvName());
		query += ",";
		query += SQLTools.GetStringForSQL("ACTIVE");
		query += ",";
		query += this.getFrameworkExecution().getMetadataControl().getResultRepositoryConfiguration()
				.getSystemTimestampExpression();
		query += ",";
		query += "null";
		query += ")";
		query += ";";

		InputStream inputStream = new ByteArrayInputStream(query.getBytes(StandardCharsets.UTF_8));
		this.getFrameworkExecution().getMetadataControl().getResultRepositoryConfiguration().executeScript(inputStream);
	}

	public void logSkip(ActionExecution actionExecution)
	{
		// Generate Process Id
		this.getNewProcessId();

		String query = "INSERT INTO "
				+ this.getFrameworkExecution().getMetadataControl().getResultRepositoryConfiguration().getMetadataTableConfiguration()
						.getTableName("ActionResults")
				+ " (RUN_ID, PRC_ID, ACTION_ID, ENV_NM, ST_NM, STRT_TMS, END_TMS)";
		query += " VALUES ";
		query += "(";
		query += SQLTools.GetStringForSQL(this.getRunId());
		query += ",";
		query += SQLTools.GetStringForSQL(this.getProcessId());
		query += ",";
		query += SQLTools.GetStringForSQL(actionExecution.getAction().getId());
		query += ",";
		query += SQLTools.GetStringForSQL(this.getEnvName());
		query += ",";
		query += SQLTools.GetStringForSQL("SKIPPED");
		query += ",";
		query += this.getFrameworkExecution().getMetadataControl().getResultRepositoryConfiguration()
				.getSystemTimestampExpression();
		query += ",";
		query += this.getFrameworkExecution().getMetadataControl().getResultRepositoryConfiguration()
				.getSystemTimestampExpression();
		;
		query += ")";
		query += ";";

		InputStream inputStream = new ByteArrayInputStream(query.getBytes(StandardCharsets.UTF_8));
		this.getFrameworkExecution().getMetadataControl().getResultRepositoryConfiguration().executeScript(inputStream);

		String status = FrameworkStatus.SKIPPED.value();

		this.logMessage(actionExecution, "action.status=" + status, Level.INFO);
	}

	public void logStart(BackupExecution backupExecution) {
		this.setRunId(this.getFrameworkExecution().getFrameworkLog().getUuid().toString());

		// Reset Process Id
		this.resetProcessId();

	}

	public void logStart(RestoreExecution restoreExecution) {
		this.setRunId(this.getFrameworkExecution().getFrameworkLog().getUuid().toString());

		// Reset Process Id
		this.resetProcessId();

	}

	private void getNewProcessId()
	{
		this.processId++;
		this.getFrameworkExecution().getFrameworkLog().log("exec.processid=" + this.getProcessId(), Level.DEBUG);
	}

	private void resetProcessId()
	{
		this.setProcessId(0);
	}

	public String logEnd(ScriptExecution scriptExecution)
	{
		String status = this.getStatus(scriptExecution);
		String query = "update "
				+ this.getFrameworkExecution().getMetadataControl().getResultRepositoryConfiguration().getMetadataTableConfiguration()
						.getTableName("ScriptResults")
				+ " set ST_NM = '" + status + "', END_TMS = " + this.getFrameworkExecution().getMetadataControl()
						.getResultRepositoryConfiguration().getSystemTimestampExpression();
		query += " where RUN_ID = '" + this.getRunId() + "' and PRC_ID = " + scriptExecution.getProcessId() + ";";

		InputStream inputStream = new ByteArrayInputStream(query.getBytes(StandardCharsets.UTF_8));
		this.getFrameworkExecution().getMetadataControl().getResultRepositoryConfiguration().executeScript(inputStream);

		// Clear processing variables
		// Only is the script is a root script, this will be cleaned
		// In other scripts, the processing variables are still valid
		if (scriptExecution.isRootScript())
		{
			this.getExecutionRuntime().cleanRuntimeVariables();
		}

		Timestamp endTimestamp = new Timestamp(System.currentTimeMillis());
		this.getScriptLog().setEnd(endTimestamp);
		this.getScriptLog().setStatus(status);
		this.getExecutionLog().setLog(this.getScriptLog());

		// return
		return status;
	}

	public void logEnd(ActionExecution actionExecution, ScriptExecution scriptExecution)
	{
		String status = this.getStatus(actionExecution, scriptExecution);
		String query = "update "
				+ this.getFrameworkExecution().getMetadataControl().getResultRepositoryConfiguration().getMetadataTableConfiguration()
						.getTableName("ActionResults")
				+ " set ST_NM = '" + status + "', END_TMS = " + this.getFrameworkExecution().getMetadataControl()
						.getResultRepositoryConfiguration().getSystemTimestampExpression();
		query += " where RUN_ID = '" + this.getRunId() + "' and PRC_ID = " + actionExecution.getProcessId() + ";";

		InputStream inputStream = new ByteArrayInputStream(query.getBytes(StandardCharsets.UTF_8));
		this.getFrameworkExecution().getMetadataControl().getResultRepositoryConfiguration().executeScript(inputStream);
	}

	public void logEnd(BackupExecution backupExecution)
	{

	}

	public void logEnd(RestoreExecution restoreExecution) {

	}

	private String getStatus(ActionExecution actionExecution, ScriptExecution scriptExecution) {
		String status = FrameworkStatus.ERROR.value();

		if (actionExecution.getActionControl().getExecutionMetrics().getSkipCount() == 0) {

			if (actionExecution.getActionControl().getExecutionMetrics().getErrorCount() > 0) {
				status = FrameworkStatus.ERROR.value();
				scriptExecution.getExecutionMetrics().increaseErrorCount(1);
			} else if (actionExecution.getActionControl().getExecutionMetrics().getWarningCount() > 0) {
				status = FrameworkStatus.WARNING.value();
				scriptExecution.getExecutionMetrics().increaseWarningCount(1);
			} else {
				status = FrameworkStatus.SUCCESS.value();
				scriptExecution.getExecutionMetrics().increaseSuccessCount(1);
			}
		} else {
			status = FrameworkStatus.SKIPPED.value();
			scriptExecution.getExecutionMetrics().increaseSkipCount(1);
		}

		this.logMessage(actionExecution, "action.status=" + status, Level.INFO);

		return status;

	}

	private String getStatus(ScriptExecution scriptExecution) {
		String status = FrameworkStatus.ERROR.value();

		if (this.isActionErrorStop()) {
			status = FrameworkStatus.STOPPED.value();
		} else if (this.isScriptExit()) {
			status = FrameworkStatus.STOPPED.value();
			// TODO: get status from input parameters in action
		} else if (scriptExecution.getExecutionMetrics().getSuccessCount() == 0
				&& scriptExecution.getExecutionMetrics().getWarningCount() == 0
				&& scriptExecution.getExecutionMetrics().getErrorCount() > 0) {
			status = FrameworkStatus.ERROR.value();
		} else if (scriptExecution.getExecutionMetrics().getSuccessCount() > 0
				&& scriptExecution.getExecutionMetrics().getWarningCount() == 0
				&& scriptExecution.getExecutionMetrics().getErrorCount() == 0) {
			status = FrameworkStatus.SUCCESS.value();
		} else {
			status = FrameworkStatus.WARNING.value();
		}

		this.logMessage(scriptExecution, "script.status=" + status, Level.INFO);
		String output = scriptExecution.getExecutionControl().getExecutionRuntime().resolveVariables("#output#");
		this.logMessage(scriptExecution, "script.output=" + output, Level.INFO);

		return status;
	}

	public void logExecutionOutput(ScriptExecution scriptExecution, String outputName, int outputValue)
	{
		this.logExecutionOutput(scriptExecution, outputName, Integer.toString(outputValue));
	}

	public void logExecutionOutput(ScriptExecution scriptExecution, String outputName, long outputValue)
	{
		this.logExecutionOutput(scriptExecution, outputName, Long.toString(outputValue));
	}

	public void logExecutionOutput(ScriptExecution scriptExecution, String outputName, String outputValue)
	{
		// Redact any encrypted values
		outputValue = this.getFrameworkExecution().getFrameworkCrypto().redact(outputValue);
		outputValue = TextTools.shortenTextForDatabase(outputValue, 2000);

		String query = "INSERT INTO " + this.getFrameworkExecution().getMetadataControl()
				.getResultRepositoryConfiguration().getMetadataTableConfiguration().getTableName("ScriptOutputs")
				+ " (RUN_ID, PRC_ID, SCRIPT_ID, OUT_NM, OUT_VAL)";
		query += " VALUES ";
		query += "(";
		query += SQLTools.GetStringForSQL(this.getRunId());
		query += ",";
		query += SQLTools.GetStringForSQL(scriptExecution.getProcessId());
		query += ",";
		query += SQLTools.GetStringForSQL(scriptExecution.getScript().getId());
		query += ",";
		query += SQLTools.GetStringForSQL(outputName);
		query += ",";
		query += SQLTools.GetStringForSQL(outputValue);
		query += ")";
		query += ";";

		InputStream inputStream = new ByteArrayInputStream(query.getBytes(StandardCharsets.UTF_8));
		this.getFrameworkExecution().getMetadataControl().getResultRepositoryConfiguration().executeScript(inputStream);
	}

	public void logExecutionOutput(ActionExecution actionExecution, String outputName, int outputValue)
	{
		this.logExecutionOutput(actionExecution, outputName, Integer.toString(outputValue));
	}

	public void logExecutionOutput(ActionExecution actionExecution, String outputName, long outputValue)
	{
		this.logExecutionOutput(actionExecution, outputName, Long.toString(outputValue));
	}

	public void logExecutionOutput(ActionExecution actionExecution, String outputName, String outputValue)
	{
		// Redact any encrypted values
		outputValue = this.getFrameworkExecution().getFrameworkCrypto().redact(outputValue);
		outputValue = TextTools.shortenTextForDatabase(outputValue, 2000);

		String query = "INSERT INTO " + this.getFrameworkExecution().getMetadataControl()
				.getResultRepositoryConfiguration().getMetadataTableConfiguration().getTableName("ActionOutputs")
				+ " (RUN_ID, PRC_ID, ACTION_ID, OUT_NM, OUT_VAL)";
		query += " VALUES ";
		query += "(";
		query += SQLTools.GetStringForSQL(this.getRunId());
		query += ",";
		query += SQLTools.GetStringForSQL(actionExecution.getProcessId());
		query += ",";
		query += SQLTools.GetStringForSQL(actionExecution.getAction().getId());
		query += ",";
		query += SQLTools.GetStringForSQL(outputName);
		query += ",";
		query += SQLTools.GetStringForSQL(outputValue);
		query += ")";
		query += ";";

		this.getFrameworkExecution().getMetadataControl().getResultRepositoryConfiguration().executeUpdate(query);
	}

	// Log message
	public void logMessage(ActionExecution actionExecution, String message, Level level)
	{
		if (!actionExecution.getScriptExecution().isRootScript())
		{
			if (level == Level.INFO || level == Level.ALL)
			{
				// do not display non-root info on info level
				// redirect to debug level
				level = Level.DEBUG;
			}
		}

		this.getFrameworkExecution().getFrameworkLog().log(message, level);
	}

	public void logMessage(ScriptExecution scriptExecution, String message, Level level)
	{
		if (!scriptExecution.isRootScript())
		{
			if (level == Level.INFO || level == Level.ALL)
			{
				// do not display non-root info on info level
				// redirect to debug level
				level = Level.DEBUG;
			}
		}

		this.getFrameworkExecution().getFrameworkLog().log(message, level);
	}

	public void endExecution()
	{
		System.out.println("++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++++");
		System.out.println("script.launcher.end");
		System.exit(0);
	}

	// Getters and Setters
	public FrameworkExecution getFrameworkExecution() {
		return frameworkExecution;
	}

	public void setFrameworkExecution(FrameworkExecution frameworkExecution) {
		this.frameworkExecution = frameworkExecution;
	}

	public String getRunId()
	{
		return runId;
	}

	public void setRunId(String runId)
	{
		this.runId = runId;
		this.getFrameworkExecution().getFrameworkLog().log("exec.runid=" + this.getRunId(), Level.INFO);
	}

	public String getEnvName()
	{
		return envName;
	}

	public void setEnvName(String envName)
	{
		this.envName = envName;
	}

	public long getProcessId()
	{
		return processId;
	}

	public void setProcessId(long processId)
	{
		this.processId = processId;
	}

	public boolean isActionErrorStop()
	{
		return actionErrorStop;
	}

	public void setActionErrorStop(boolean actionErrorStop)
	{
		this.actionErrorStop = actionErrorStop;
	}

	public ExecutionRuntime getExecutionRuntime()
	{
		return executionRuntime;
	}

	public void setExecutionRuntime(ExecutionRuntime executionRuntime)
	{
		this.executionRuntime = executionRuntime;
	}

	public List<Long> getProcessIdList()
	{
		return processIdList;
	}

	public void setProcessIdList(List<Long> processIdList)
	{
		this.processIdList = processIdList;
	}

	public ExecutionTrace getExecutionTrace()
	{
		return executionTrace;
	}

	public void setExecutionTrace(ExecutionTrace executionTrace)
	{
		this.executionTrace = executionTrace;
	}

	public ScriptLog getScriptLog()
	{
		return scriptLog;
	}

	public void setScriptLog(ScriptLog scriptLog)
	{
		this.scriptLog = scriptLog;
	}

	public ExecutionLog getExecutionLog()
	{
		return executionLog;
	}

	public void setExecutionLog(ExecutionLog executionLog)
	{
		this.executionLog = executionLog;
	}

	public boolean isScriptExit() {
		return scriptExit;
	}

	public void setScriptExit(boolean scriptExit) {
		this.scriptExit = scriptExit;
	}
}