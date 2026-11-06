# Host and process counters

Host metrics and Java process CPU require a running Scouter Host Agent. The CLI reads the Collector's cached data; it does not collect operating-system metrics itself.

## Connect the producers

Run the Host Agent and Java Agent on the same host, with a shared writable process registry and the same PID view. Set this in both Agent configurations:

```properties
counter_object_registry_path=/path/to/shared-registry
```

The Java Agent writes its PID and object name there. The Host Agent uses those registrations to emit `ProcCpu` for the Java object. A registry shared between unrelated PID namespaces is insufficient.

Point both Agents at the intended Collector. Use `object list` to find the host object and Java object separately, then query their hashes:

```bash
"${SCOUTERCTL[@]}" counter list --object-hash "$HOST_HASH"
"${SCOUTERCTL[@]}" counter get --object-hash "$HOST_HASH" --counter Cpu
"${SCOUTERCTL[@]}" counter get --object-hash "$HOST_HASH" --counter MemU
"${SCOUTERCTL[@]}" counter get --object-hash "$HOST_HASH" --counter DiskWriteBytes
"${SCOUTERCTL[@]}" counter get --object-hash "$HOST_HASH" --counter NetRxBytes
"${SCOUTERCTL[@]}" counter get --object-hash "$JAVA_HASH" --counter ProcCpu
```

Units are supplied by the Collector definitions and values are returned without conversion. Do not assume that raw CPU readings from different Host Agent implementations are directly comparable. Missing values remain `unavailable`/null, including when the producer does not send that field. A genuine zero is `available` with a numeric string.

## Tested combinations

All processes below ran on Linux/WSL with Temurin Java 8 (`1.8.0_502`). The CLI ran on JDK 21, with actual Linux and native Windows client checks.

| Collector / Java Agent | Host Agent | Observed result |
| --- | --- | --- |
| 2.8.1 | 2.8.1 | Process CPU, host CPU/memory, disk and network counters available |
| 2.21.3 | 2.21.3 | Process CPU and host CPU/memory available; disk/network counters remained unavailable |
| 2.21.3 | 2.8.1 | Process CPU, host CPU/memory, disk and network counters available |

The official 2.21.3 Host Agent still returned no `DiskReadBytes`, `DiskWriteBytes`, `NetRxBytes` or `NetTxBytes` after HTTP load, a synced disk write, external network reception and more than two 10-second collection intervals. Inspection of the executed Host Agent JAR found I/O-rate computation without those values being added to its outgoing performance pack. Keeping Collector/Java Agent 2.21.3 and changing only the Host Agent to 2.8.1 restored those fields in this test.

This is a producer limitation observed in these releases, not evidence that the Collector rejected the requests. No upstream Agent patch or synthetic fallback was applied. Other Host Agent versions, Windows-hosted Agents and macOS have not been established by these checks.

## Reproduce locally

Use isolated configurations and data. Select extracted official distributions and the existing Java 8 Kotlin fixture as described in [supported versions](supported-versions.md#reproduce-the-checks). Additionally set:

```bash
export SCOUTER_HOST_JAVA="$JAVA8/bin/java"
export SCOUTER_HOST_JAR="$HOST_DIST/agent.host/scouter.host.jar"
# Optional real reception over a non-loopback interface:
export SCOUTER_HOST_NETWORK_URL="https://api.github.com/repos/scouter-project/scouter/releases/tags/v2.21.3"
./gradlew hostCounterSmoke -PscouterRoot="$SCOUTER_SOURCE"
```

`HOST_DIST` is the extracted distribution supplying the Host Agent. The smoke starts its own Collector, Host Agent and Java service, uses a private registry, drives real SQL HTTP requests, writes and syncs a 64 MiB temporary file, and stops its child processes. An optional network response is discarded and bounded to 64 MiB. Host network collection excludes loopback traffic; local HTTP requests alone do not prove a nonzero network rate.

The default smoke requires disk/network values. For the known 2.21.3 missing-field case, `SCOUTER_HOST_EXPECT_IO=false` instead verifies that those fields remain unavailable after the collection interval. That result must not be reported as successful I/O collection.

Optional native Windows client verification uses `SCOUTER_WINDOWS_JAVA` as a WSL executable path to Windows `java.exe`. Set `SCOUTER_WINDOWS_JAR` and `SCOUTER_WINDOWS_CONFIG` to native Windows paths for a local copy of the same CLI JAR and a nonexistent test config file. The helper passes credentials through the child environment and scopes `WSLENV` to that process.

Host-wide counters include activity from other processes and devices. They do not attribute all observed bytes or CPU use to the test service. The HTTP success counts, synced file size and network response bytes provide separate evidence of the generated work.
