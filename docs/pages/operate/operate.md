{% include navigation.html %}

# Operate the framework

## Manage Metadata Configuration

The configuration stored in the configuration repository is managed via the `bin/iesi-metadata.sh` (or `bin/iesi-metadata.cmd` on Windows) command. 

The following options are available for the command:

|Option|Description|
|------|-----------|
|-backup|create a backup of the entire metadata repository|
|-config|define the metadata repository config|
|-clean|clean all tables in the metadata repository|
|-create|create all metadata tables in the metadata repository|
|-drop|drop all metadata tables in the metadata repository|
|-files|filename(s) to load from the input folder into the metadata repository|
|-help|displays the help message|
|-load|load metadata file from the input folder into the metadata repository|
|-path|path to be used to for backup or restore|
|-restore|restore a backup of the metadata repository|
|-type|define the type of metadata repository|

**Tip**
* It is possible to combine several actions in a single command according to the following order: drop, create, clean, load

### Loading configuration

All configuration items of the framework are structured using the json format. 
The different templates help to generate the correct structure easily. 
In this way, an item can be loaded into the configuration repository through the `metadata/in/new` folder. 
* Place the configuration json file in the `metadata/in/new` folder
* Execute the `bin/iesi-metadata.sh` (or `bin/iesi-metadata.cmd` on Windows) command with following options:
  * -load
  * (optional) -files [expression]
  
**File expression options**
* a single file name including extension
```
Example: Script.json
```
* list of files separated by commas
```
Example: Script1.json,Script2.json
```
* a regular expression written as function
```
=regex([your expression])
Example: =regex(.+\json) > this will load all files
```

## Encrypt Credentials

All credentials need to be encrypted via the `bin/iesi-encrypt.sh` (or `iesi-encrypt.cmd` on Windows) command. 
* Encrypted values always apply the following syntax `ENC([EncryptedValue])`

## Execute Script

The automation scripts can be executed via the `bin/iesi-launch.sh` (or `iesi-launch.cmd` on Windows) command. 
The appropriate metadata repositories as defined in the framework's settings will be used to retrieve the configuration and store the execution results. 

The following options are available for the command:

|Option|Description|
|------|-----------|
|-actions <arg>|select actions to execute or not|
|-env <arg>|define the environment name where the execution needs to take place|
|-help|displays the help message|
|-impersonation <arg>|define impersonation name to use|
|-impersonate <arg>|define custom impersonations to use|
|-file <arg>|define the file to execute|
|-paramlist <arg>|define a list of parameters to use|
|-paramfile <arg>|define a parameter file to use|
|-script <arg>|define the script name to execute|
|-settings <arg>|set specific setting values|
|-version <arg>|define the version of the script to execute|

### Basic Execution

The most basic method to execute a script is to use the `script` and `environment` option: execute a script on a given environment. 
The environment where an execution takes place is always required.

```bash
bin/iesi-launch.sh -script <arg> -env <arg>
```

### Adding User-Defined Parameters

It is possible to add one or more user-defined parameters from the commandline when starting the script execution.
* The `paramlist` option allows to specify parameters and values directly on the commandline
```
-paramlist var1=value1,var2=value2
```

* The `paramfile` option allows to specify one or more key-value files to be used (separated by comma)
```
Single file: -paramfile /path-to-folder/file.conf
Multiple files: -paramfile /path-to-folder/file1.conf,/path-to-folder/file2.conf
```
  * The parameter file path needs to be included
  * The structure of the parameter file is as follows:
  ```
  parameter1=value1
  parameter2=value2
  ...
  parameterN=valueN
  ```

### Selecting Specific Actions to Execute

It is possible to select a subset of actions to execute or skip using the `actions` option.

```
-actions type=number,mode=<include/exclude>,scope=<range of numbers>
```

* The **type** identifies how to identify relevant actions to evaluate
  * Possible values: number
  * number: the action number will be used for evaluation
* The **mode** identifies if the actions in scope need to be included or excluded from execution
  * Possible values: include, exclude
  * include: the actions in scope will be executed, the others not
  * exclude: the actions in scope will be skipped, the others will be executed
* The **scope** will identify the actions to evaluate
  * Possible ranges: single action, multiple actions, range of actions
  * single action: a single action number – example: 2
  * multiple actions: several action numbers separated by comma – example 2,6
  * range of actions: a range having a start and end action number (both included in the scope definition) – example 2-4
  * combinations of different types are possible – example 2-4,6

### Defining Specific Setting Values

It is possible to define specific setting valus when executing a script using the `settings` option.

```
-settings [SETTING]=[VALUE]
```

|Setting|Description|Values|
|-------|-----------|------|
|iesi.commandline.display.runtime.variable|Display all operations when setting runtime variables|Y,N|

## External triggering

* The solution can be executed via command line scripts that are located in `#iesi.home#/bin`
* A separate version is available for N*X and Windows platforms.
* External triggering of the scripts is possible using default methods, examples include:
  * Scheduling using cron
  * Enterprise scheduler integration (e.g. Tivoli, Opswise, UC4, etc.)
  * Triggering via Jenkins
    * via the standard ssh panel in a Jenkins project
    * Via Groovy scripts (recommended for versioning and control – scripts are located in scm)
    * Etc.
  * Triggering via Ansible playbooks
  * Etc.

We are continuously working to improve execution triggering and execution management. 
New features in the future will include rest server triggering, internal dependency broker, internal scheduler, 
user interface, etc.
