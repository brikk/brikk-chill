# Install the OpenSearch plugin

Build the installable archive with:

```sh
./gradlew :opensearch-plugin:pluginZip
```

The result is `opensearch-plugin/build/distributions/chill-script-<version>-os-<opensearch-version>.zip`.
It contains the plugin descriptor, permissions, and runtime JARs. The OpenSearch installer requires
the descriptor's OpenSearch version to match the node.

Releases and snapshots publish the same archive as a Maven artifact:

```text
dev.brikk.chill:chill-opensearch-plugin:<version>:os-<opensearch-version>@zip
```

Both CI workflows also retain the ZIP under their `chill-opensearch-plugin` artifact. Download
and extract the CI artifact wrapper to get the installable ZIP inside it.

Install on each node, then restart OpenSearch:

```sh
/usr/share/opensearch/bin/opensearch-plugin install --batch file:///absolute/path/to/chill-plugin.zip
```

`./gradlew :opensearch-plugin:test` publishes to a local repository under `build/` and checks that
the published ZIP is byte-for-byte identical to the installable archive. Nothing is uploaded by
that test. `./gradlew :opensearch-plugin:integrationTest` installs that archive in a real OpenSearch
container and exercises score, filter, field, and stored scripts. The latter requires Docker.

`./gradlew :opensearch-plugin:packagingTest` checks incremental builds in a temporary project copy.
It runs the real-node suite, confirms an unchanged build skips it, then changes only the ZIP's
permissions file and checks that the rebuilt archive is installed and tested again. It also requires
Docker and runs as part of `check`.
