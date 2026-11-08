# Host and process counters

Host metrics and Java process CPU require a running Scouter Host Agent. The CLI reads the Collector's cached data; it does not collect operating-system metrics itself.

## Connect the producers

Run the Host Agent and Java Agent on the same host, with a shared writable process registry and the same PID view. Set this in both Agent configurations:

```properties
counter_object_registry_path=/path/to/shared-registry
```

The Java Agent writes its PID and object name there. The Host Agent uses those registrations to emit `ProcCpu` for the Java object. A registry shared between unrelated PID namespaces is insufficient.

Use a [selected connection and password](connections.md). Point both Agents at the intended Collector. Use `object list` to find the host object and Java object separately, then query their hashes:

```bash
scouterctl counter list --object-hash "$HOST_HASH"
scouterctl counter get --object-hash "$HOST_HASH" --counter Cpu
scouterctl counter get --object-hash "$HOST_HASH" --counter MemU
scouterctl counter get --object-hash "$HOST_HASH" --counter DiskWriteBytes
scouterctl counter get --object-hash "$HOST_HASH" --counter NetRxBytes
scouterctl counter get --object-hash "$JAVA_HASH" --counter ProcCpu
```

Units are supplied by the Collector definitions and values are returned without conversion. Do not assume that raw CPU readings from different Host Agent implementations are directly comparable. Missing values remain `unavailable`/null, including when the producer does not send that field. A genuine zero is `available` with a numeric string.

## Tested combinations

The Collector and Agents below ran on Linux with Temurin Java 8 (`1.8.0_502`). The CLI ran on JDK 21, with actual Linux and native Windows client checks.

| Collector / Java Agent | Host Agent | Observed result |
| --- | --- | --- |
| 2.8.1 | 2.8.1 | Process CPU, host CPU/memory, disk and network counters available |
| 2.21.3 | 2.21.3 | Process CPU and host CPU/memory available; disk/network counters remained unavailable |
| 2.21.3 | 2.8.1 | Process CPU, host CPU/memory, disk and network counters available |

In the checked 2.21.3 Host Agent binary, disk/network rate values were not added to the outgoing performance pack. Changing only the Host Agent to 2.8.1 restored those fields. The CLI preserves their unavailable status and does not synthesize replacement values.

Other Host Agent versions and Windows/macOS-hosted Agents are outside these checks. Host-wide counters include activity from other processes; they do not attribute all observed CPU or I/O to one application.

## Reproduce locally

See [Host checks](testing.md#host-counters) for the isolated integration task and the known missing-I/O case.
