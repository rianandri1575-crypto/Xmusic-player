# XMusic 2.2 Runtime Fix

This patch removes the playback-service connection from the Activity launch path and makes it lazy: MediaSession/ExoPlayer is created only when the user actually starts playback.

It also disables Media3 float-output mode for the custom PCM-16 DSP processor so the processor is not handed PCM float data that it cannot process.

The existing EQ/crossover DSP architecture is preserved for 16-bit PCM playback.

This is a runtime-hardening patch based on the source available here. A device crash log would still be required to prove an unrelated crash cause.
