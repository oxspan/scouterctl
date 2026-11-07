# Required runner delays

Linux, Windows and macOS checks remain required for new releases. A job waiting for a runner provides no test evidence. The job's `timeout-minutes` limits execution after it starts; it is not a queue deadline.

## Inspect before retrying

Open the run in Actions, or inspect it with the GitHub CLI:

```sh
gh run view RUN_ID --json headSha,status,conclusion,jobs,url
```

Check the exact head SHA, job status, runner assignment, started steps and annotations. A workflow waiting on concurrency or approval is different from a platform job waiting for runner capacity. Do not approve a workflow or change permissions as a queue workaround. If a test step ran and failed, read its output and investigate that failure.

## Bounded recovery

1. After 10 minutes without a runner or started steps, report the affected platform, run link, SHA and elapsed wait. Check [GitHub Status](https://www.githubstatus.com/) and retain the run's annotations.
2. If the evidence identifies runner allocation failure and the run has finished unsuccessfully, retry the affected job once. Use `gh run rerun RUN_ID --job JOB_ID`, where `JOB_ID` is the job's numeric database ID, or **Re-run failed jobs** in Actions. An active queued job must first be cancelled deliberately; cancellation can also stop other jobs, so inspect the whole run before doing so.
3. If the retry remains unassigned for another 10 minutes or fails for the same infrastructure reason, stop retrying and report the platform check as pending or blocked. Keep the successful artifacts and original failure evidence. An unassigned retry can be cancelled after checking other work in the run. Do not treat a queue timeout as a passing test.

A rerun uses the original SHA and ref. If source or package bytes change, validate the new candidate. Do not change runner architecture, disable required checks, or automatically publish using an unrelated successful run. Any exceptional publication requires a separately reviewed candidate, matching source and package hashes, actual platform evidence and an explicit release decision. An older release's one-off recovery is not a standing bypass.

This is an operator procedure, not an automatic retry loop. It needs no extra token, scheduled workflow or repository permission. For a release that already exists but has missing assets, use the separate [upload recovery procedure](releasing.md#recover-an-interrupted-upload).

References: [GitHub reruns](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/re-run-workflows-and-jobs), [job timeout](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax#jobsjob_idtimeout-minutes).
