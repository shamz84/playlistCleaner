# Consumer R8 rules for :core:sync.

# WorkManager persists worker class names in its database and SyncWorkerFactory matches on them.
# Our workers take extra constructor arguments, so WorkManager's own keep rule (which matches the
# two-argument constructor) does not cover them; keep their names stable across releases.
-keepnames class * extends androidx.work.ListenableWorker
