# CI troubleshooting

Required release checks run on Linux and Windows. The optional scale workflow also includes macOS; that diagnostic job is not a release-support claim.

Inspect the exact revision and failed job before rerunning:

```sh
gh run view RUN_ID --json headSha,status,conclusion,jobs,url
```

| Failure | Action |
| --- | --- |
| Test or package step failed | Read that step's output and fix the cause before retrying. |
| Runner allocation or GitHub service incident | Check [GitHub Status](https://www.githubstatus.com/). Once the infrastructure issue is resolved, rerun the affected job. |
| Approval or permissions required | Ask the maintainer to review the request; do not change permissions as a queue workaround. |
| Account billing or spending limit | Ask the account owner to resolve it. Repeating the job does not fix the account setting. |

A queued job has not produced test results. `timeout-minutes` limits execution after a job starts, not its time waiting for a runner. A rerun uses the original SHA/ref; source or package changes need checks for the new revision. Preserve successful artifacts and distinguish skipped, pending and failed checks.

For a completed run, rerun a specific job with `gh run rerun RUN_ID --job JOB_ID`, using its numeric job ID. For a release with missing assets, use [asset recovery](releasing.md#publish-and-recover-assets). See [GitHub rerun documentation](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/re-run-workflows-and-jobs) for permissions and limits.
