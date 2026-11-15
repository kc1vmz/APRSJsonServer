# APRS JSON Server and Client

Two independently runnable Java 21 Spring Boot applications. 

The server receives APRS from configured inputs and publishes LoRa APRS JSON v1.0 over HTTP NDJSON.

The client subscribes from Java and displays results in a browser.

## Build and run

From the project root with Java 21 and Maven:

```shell
mvn verify
java -jar server/target/aprs-json-server-1.0.1-exec.jar
java -jar client/target/aprs-json-client-1.0.1-exec.jar
```

Open the server management page at `http://127.0.0.1:8180/` and add a connection.
For APRS-IS, enter the upstream host, port, callsign, and optional filter. A
blank passcode on a new connection uses the receive-only login value `-1`.
A blank passcode on edit retains the saved value.

Open the Java client at `http://127.0.0.1:8182/`. Enter the server address and
packet API port, then choose **Connect** for the combined feed or **Discover
feeds** to select a source. The optional custom NDJSON stream URL overrides
those fields. To run a second Java client, launch another instance with
`--server.port=8183`.

The server supports APRS-IS, KISS TCP, KISS Serial, TNC2 TCP, TNC2 Serial, and
Kenwood Serial. Serial discovery permits manual device entry. Serial setup
uses jSerialComm on Windows and Linux. The host account needs access to any
configured serial devices. Kenwood command mode uses the Welcome Center
TM-D710G initialization sequence adapted for reception.

## Server settings

| Property | Default | Environment variable | Purpose |
| --- | --- | --- |
| `server.address` | `0.0.0.0` | APRSJS_BIND_ADDRESS | Management listener address |
| `server.port` | `8180` | APRSJS_HTTP_PORT | Management listener port |
| `aprs.api.address` | `127.0.0.1` | APRSJS_API_BIND_ADDRESS | Packet API listener address |
| `aprs.api.port` | `8181` | APRSJS_API_HTTP_PORT | Packet API listener port |
| `aprs.station` | `NOCALL` | APRSJS_STATION | Initial combined-feed producer station |
| `aprs.config-file` | `data/connections.json` | APRSJS_CONFIG_FILE | Persistent source settings |


Override properties with `--name=value` on the command line or set the environment 
variable prior to starting the application. For LAN access,
bind each listener to the intended local interface. Both listeners run without
application authentication. Forward only the packet API port if publishing
the feed externally. The management listener serves its own routes on a separate
socket. The configuration file contains APRS-IS login values and should be
accessible only to the account running the server. Public feed labels should
contain only information intended for packet subscribers.

Set **Producer station** on the management page. The server saves it beside
the connection file as `connections.json.producer.json` with the default path.
The saved setting takes precedence over `aprs.station` on restart. New stream
connections to the combined feed receive it as `producer.station` in `hello`.
Set a source's producer station in **Add connection** or **Edit**. APRS-IS
always uses its login callsign, and Kenwood defaults to MYCALL. Existing KISS
and TNC2 sources without a producer station are initialized once from the
combined-feed station and saved. Changing a source station ends that source's
open streams. Reconnect to receive the new identity.

## Client settings

| Property | Default | Environment variable | Purpose |
| --- | --- | --- |
| `server.address` | `0.0.0.0` | APRSJC_BIND_ADDRESS | Management listener address |
| `server.port` | `8182` | APRSJC_HTTP_PORT | Management listener port |

Override properties with `--name=value` on the command line or set the environment 
variable prior to starting the application. For LAN access,
bind the listener to the intended local interface. The listener runs without
application authentication. 


## Packet API

All paths below are relative to the packet API listener.

| Method and path | Response |
| --- | --- |
| `GET /api/v1/aprs/stream` | Combined live NDJSON stream |
| `GET /api/v1/aprs/events?limit=10` | Combined snapshot, newest first |
| `GET /api/v1/aprs/feeds` | Feed directory extension |
| `GET /api/v1/aprs/feeds/{sourceId}/stream` | Source-specific live NDJSON stream |
| `GET /api/v1/aprs/feeds/{sourceId}/events?limit=10` | Source-specific snapshot extension |

The directory is a JSON array. Every entry has `id`, `label`, `type`,
`active`, `health`, `producerStation`, and a same-origin relative `streamUrl`. The combined
entry uses ID `all` and type `COMBINED`. Source IDs are UUIDs persisted across
edits and restarts. Source health is `PAUSED`, `CONNECTING`, `CONNECTED`, or
`RECONNECTING`. The directory exposes the configured public label and
transport type. Source endpoint settings are managed on the management listener.

Each stream sends `hello` first, then reception records and idle heartbeats.
The combined feed and each source feed have independent boot IDs and contiguous
reception sequences. A reception seen on both views carries identical packet
bytes and a different event ID per view. Within a feed, subscribers see the
same event identity. The `receiver.interface` field identifies the source
UUID. Pause and reconnect leave existing streams open. Deletion ends that
source's stream and unknown source paths return HTTP 404. Slow subscribers
are disconnected when their bounded delivery queue fills.

Streams start with live traffic. The snapshot holds up to 32 receptions per
feed in memory and accepts limits from 1 to 32. No packet data is written to
disk. The server advertises no history resume capability and returns
`history_resume_unsupported` for nonempty `after` parameters.

## Protocol interpretation

The baseline is [LoRa APRS JSON protocol revision d2c16da](https://github.com/moricef/LoRa_APRS_JSON_Protocol/tree/d2c16da52952d2d944e7e8efb8213b402cccef30).
The spec describes `rx` in terms of LoRa reception. This server applies its
envelope to APRS-IS and conventional RF observations as well. The configured
source's producer station identifies the receiver on both the source and combined feeds.
Each observation is independent, including repeated copies heard on
different inputs. Feed discovery and per-source URLs are server extensions.

The authoritative TNC2 bytes use Base64. UTF-8 text is included when it can
round-trip. KISS creates a TNC2 header from AX.25 addresses and retains the
information bytes. Line-based input retains bytes up to the monitor delimiter.
Kenwood may reconstruct a monitor packet from separate header and payload
lines. The server can preserve only the bytes delivered by the upstream
interface.

These adapters supply packet bytes without RF metadata or an explicit RXT
boundary. The protocol requires radio parameters for decoded RXT. A suffix
that resembles RXT is ambiguous on these inputs and is retained as packet
data. LoRa metric extraction, firmware and Graywolf interoperability testing,
and decoded APRS convenience fields are outside this implementation.

## License and provenance

APRS JSON Server is licensed under the GNU General Public License version 3.
