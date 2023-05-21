# HTTP Metabase Driver

This is a proof-of-concept HTTP "driver" for [Metabase](https://metabase.com/).

Previous discussion: https://github.com/metabase/metabase/pull/7047

## Usage

Currently the simplest "native" query for this driver is simply an object with a `url` property:

```json
{ "url": "https://api.coinmarketcap.com/v1/ticker/" }
```

The driver will make a `GET` request and parse the resulting JSON array into rows. Currently it only supports JSON.

You can provide a different `method` as well as `headers` and a JSON `body`:

```json
{
  "url": "https://api.coinmarketcap.com/v1/ticker/",
  "method": "POST",
  "headers": {
    "Authentication": "SOMETOKEN"
  },
  "body": {
    "foo": "bar"
  }
}
```

Additionally, you can provide a `result` object with a JSONPath to the "root" in the response, and/or a list of `fields`:

```json
{
  "url": "https://blockchain.info/blocks?format=json",
  "result": {
    "path": "blocks",
    "fields": ["height", "time"]
  }
}
```

You can also predefine "tables" in the database configuration's `Table Definitions` setting. These tables will appear in the graphical query builder:

```json
{
  "tables": [
    {
      "name": "Blocks",
      "url": "https://blockchain.info/blocks?format=json",
      "fields": [
        { "name": "height", "type": "number" },
        { "name": "hash", "type": "string" },
        { "name": "time", "type": "number" },
        { "type": "boolean", "name": "main_chain" }
      ],
      "result": {
        "path": "blocks"
      }
    }
  ]
}
```

There is limited support for aggregations and breakouts, but this is very experimental and may be removed in future versions.

## Building the driver

### Prereq: Install Metabase as a local maven dependency, compiled for building drivers

Clone the [Metabase repo](https://github.com/metabase/metabase) first if you haven't already done so.

### Metabase 0.46.0

- The process for building a driver has changed slightly in Metabase 0.46.0. Your build command should now look
  something like this:

  ```sh
  # Example for building the driver with bash or similar

  # switch to the local checkout of the Metabase repo
  cd /path/to/metabase/repo

  # get absolute path to the driver project directory
  DRIVER_PATH=`readlink -f ~/path/to/metabase-http-driver`

  # Build driver. See explanation in sample HTTP driver README
  clojure \
    -Sdeps "{:aliases {:http {:extra-deps {com.metabase/http-driver {:local/root \"$DRIVER_PATH\"}}}}}"  \
    -X:build:http \
    build-drivers.build-driver/build-driver! \
    "{:driver :http, :project-dir \"$DRIVER_PATH\", :target-dir \"$DRIVER_PATH/target\"}"
  ```

  Take a look at our [build instructions for the sample Sudoku driver](https://github.com/metabase/sudoku-driver#build-it-updated-for-build-script-changes-in-metabase-0460) for an explanation of the command.

  Note that while this command itself is quite a lot to type, you no longer need to specify a `:build` alias in your
  driver's `deps.edn` file.

  Please upvote https://ask.clojure.org/index.php/7843/allow-specifying-aliases-coordinates-that-point-projects ,
  which will allow us to simplify the driver build command in the future.


### Copy it to your plugins dir and restart Metabase

```bash
mkdir -p /path/to/metabase/plugins/
cp target/uberjar/http.metabase-driver.jar /path/to/metabase/plugins/
jar -jar /path/to/metabase/metabase.jar
```

### Or [Adding external dependencies or plugins](https://www.metabase.com/docs/latest/installation-and-operation/running-metabase-on-docker#adding-external-dependencies-or-plugins)
To add external dependency JAR files, you’ll need to:

  - create a plugins directory in your host system
  - bind that directory so it’s available to Metabase as the path /plugins (using either --mount or -v/--volume). 

For example, if you have a directory named /path/to/plugins on your host system, you can make its contents available to Metabase using the --mount option as follows:

```bash
docker run -d -p 3000:3000 \
  --mount type=bind,source=/path/to/plugins,destination=/plugins \
  --name metabase metabase/metabase
```
    
### Note that Metabase will use this directory to extract plugins bundled with the default Metabase distribution (such as drivers for various databases such as SQLite), thus it must be readable and writable by Docker.