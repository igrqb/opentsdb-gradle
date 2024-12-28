# opentsdb-gradle
OpenTSDB 2.x Gradle port

## Running TSDB
(Tested in IntelliJ IDEA)

1. Navigate to `net.opentsdb.tools.TSDMain`
2. Run as Java Application
3. Modify CLI arguments to: `--config=src/test/resources/opentsdb.conf`
4. Make sure working directory is same as repository root.


## Known Issues and TODOs

1. Form controls currently not showing correctly in browser: `http://localhost:4242`
2. Need to upgrade a number of vulnerable dependencies.