# Clinical Live Transcript Validation

Validation date: 2026-07-29

## Test Material

Two fictional clinical dictations were synthesized at 155 words per minute:

| Report | Reference words | Audio duration |
| --- | ---: | ---: |
| Pathology lung resection report | 267 | 144.5 seconds |
| Radiology CT chest, abdomen, and pelvis report | 311 | 175.2 seconds |

The reports included measurements, staging, negative findings, pathology stains,
anatomic locations, and difficult clinical terms.

## Method

The same rolling decoder, provisional/finalized transcript state, overlap
stitching, and timestamp calculations used by `live_whisper_demo.py` were fed
audio on a simulated real-time clock. Actual model execution time advanced that
clock, so slow decoding caused the same skipped refresh windows as live capture.

The English live model was `distil-small.en`. The final offline model was
`large-v3`. Both ran on CPU with `int8` compute.

## Results

| Metric | Pathology | Radiology |
| --- | ---: | ---: |
| Live words | 263 | 321 |
| Live word error rate | 23.2% | 21.2% |
| Live median decode time | 1.19 seconds | 1.13 seconds |
| Live 95th percentile decode time | 1.44 seconds | 1.31 seconds |
| Median timestamp drift | -0.04 seconds | -0.01 seconds |
| 95th percentile absolute timestamp drift | 1.44 seconds | 1.40 seconds |
| Final `large-v3` words | 258 | 315 |
| Final `large-v3` word error rate | 15.0% | 13.2% |
| Final processing time | 143.0 seconds | 213.0 seconds |

Typical live text appeared about two to four seconds after completed speech. This
includes a two-second stability lag that prevents provisional text from deleting
or duplicating previously finalized speech.

## Findings

- Full audio is preserved independently of live decoder speed.
- Live timestamps are synchronized closely enough for event correlation.
- The original moving-window implementation deleted older text at window
  boundaries. Finalized and provisional transcript state now prevents that.
- Exact boundary words could be repeated when Whisper revised word timestamps.
  Suffix/prefix stitching now removes those duplicates.
- `small.en` could not reliably keep up with the rolling window on the test Mac.
  `distil-small.en` is now used for English live text, while the configured
  high-accuracy model still regenerates the final transcript.
- Neither live nor final transcription is verbatim for difficult clinical
  vocabulary. The saved audio remains the source for audit or correction.

## Hardware Note

The installed Dubbing Virtual Device was unsuitable for end-to-end loopback
timing because it delivered audio frames substantially faster than wall time.
Built-in microphone capture and WAV export were validated separately. The
repeatable clinical measurements above therefore use deterministic real-time
chunk delivery rather than that virtual driver.
