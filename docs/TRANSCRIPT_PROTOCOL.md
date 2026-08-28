# TimeStamp transcript helper protocol

The QuPath extension and `scripts/live_whisper_demo.py` communicate over stdout using
UTF-8, one message per line. Protocol fields are separated by a single tab. Human-readable
diagnostic lines may also be written, but the extension treats any unrecognized first field
as a log line and never as a state transition.

| Message | Fields | Meaning |
| --- | --- | --- |
| `DEVICE` | stable device name, display label | One microphone returned by `--list-devices`; an empty name means system default. |
| `AUDIO_CHECK_READY` | none | `--check-audio` has opened the input stream. |
| `AUDIO_CHECK_RESULT` | SNR dB, signal state | Terminal microphone-test signal quality. SNR is `-1` while calibration is incomplete. |
| `AUDIO_LEVEL` | SNR dB, signal state | Periodic signal quality. State is `calibrating`, `critical`, `low`, or `good`; 8 dB is recommended and below 3 dB is critical. |
| `AUDIO_CLIPPING` | percent of samples at full scale | At least 0.1% of a raw input chunk reached full scale. Emitted once per capture; the user should lower microphone gain. |
| `AUDIO_SILENT` | quiet seconds | No usable input has been observed for the watchdog interval. Emitted once per quiet stretch. |
| `AUDIO_RECOVERED` | none | Input recovered after `AUDIO_SILENT`. |
| `TRANSCRIPT_READY` | none | Audio capture is open. Recording may begin even while the model is warming. |
| `RECORDING_ORIGIN` | ISO-8601 UTC instant | ADC-derived start of audio frame zero. This is the only recording time origin. |
| `LIVE_MODEL_READY` | model name | Live decoding can begin; already-buffered audio remains available. Parakeet reports `parakeet-mlx:<model-id>` so the UI/log can distinguish the engine. |
| `TRANSCRIPT_UPDATED` | none | The committed live transcript file changed. |
| `TRANSCRIPT_PARTIAL` | provisional text | Replaces the uncommitted live tail shown below the transcript. An empty field clears it. This text is never saved. |
| `FINALIZE_PROGRESS` | processed audio seconds, total audio seconds | Progress through the full saved-audio pass. |
| `FINALIZATION_RESULT` | result code | Terminal process outcome such as `paused` for capture-only exit, or final-pass results including `final`, `live-fallback-empty`, and `failed`. |

Rules:

- A known message with the wrong number or type of fields is malformed and must not change UI state.
- Message fields must not contain tabs or newlines; the helper replaces them with spaces.
- `TRANSCRIPT_READY` does not establish the recording clock. Only `RECORDING_ORIGIN` does.
- Elapsed event times are derived when saving from `event.recordedAtUtc - RECORDING_ORIGIN`.
  Missing or negative elapsed values are serialized as empty CSV cells and JSON `null`, never fabricated as zero.
- `FINALIZATION_RESULT` is emitted exactly once before normal helper exit. A
  capture-only pause emits `paused`; only `--finalize-existing` performs the
  offline final pass used by Done.
