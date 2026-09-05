# Publishing and releases

## Branch triggers

| Trigger | Maven destination | GitHub output |
|---|---|---|
| Push to `main` | Central snapshots repository, using the `-SNAPSHOT` version in `gradle.properties` | Workflow artifact containing the OpenSearch plugin ZIP |
| Push to `release/<version>` | Maven Central, using `<version>` | Release tagged `v<version>` with the installable OpenSearch plugin ZIP attached |
| Manual release workflow with a version input | Same as a release branch, using the selected ref and supplied version | Same tagged release and ZIP |

For example, pushing `release/0.1.0` builds with `-Pversion=0.1.0`, runs the tests, stages signed
publications for all modules, and submits the bundle to Central with automatic publication enabled.
The workflow then creates GitHub Release `v0.1.0` at the exact tested commit, with generated release
notes and `chill-script-0.1.0-os-<opensearch-version>.zip` as an asset. A version with a qualifier,
such as `0.1.0-rc1`, creates a GitHub prerelease.

There is no need to remove `-SNAPSHOT` from `gradle.properties` when creating a release branch;
the workflow overrides the version. All pushes to a release branch trigger publication, so finish
the changes before pushing it. Existing release tags are refused before building or uploading.
Release runs are serialized to avoid two runs attempting to publish the same version at once.

Central processes uploads asynchronously. The workflow preserves the existing no-wait behavior:
a successful upload means Central accepted the bundle, not that validation and publication have
finished. The run summary records the deployment ID; check the Central Portal for its final status.

## Existing credentials

The workflows use the existing Brikk organization secrets; no new publishing credentials are needed:

- `KOTLIN_TOOLCHAIN_MAVENCENTRAL_USERNAME`
- `KOTLIN_TOOLCHAIN_MAVENCENTRAL_PASSWORD`
- `KOTLIN_TOOLCHAIN_SIGNING_KEY`
- `KOTLIN_TOOLCHAIN_SIGNING_PASSPHRASE`

The existing `MAVEN_CENTRAL` spelling aliases are also supported for username and password.
Snapshots do not require signing. Releases use the signing key and its passphrase. GitHub Release
creation uses the workflow's built-in `GITHUB_TOKEN` with `contents: write`, not a personal token.

## Failed releases

If build or upload fails, no GitHub Release is created. If Central accepts the upload but GitHub
Release creation fails, check Central before retrying: Maven release versions cannot be overwritten.
When Central has already published the version, recover the GitHub Release using the exact commit
and the plugin ZIP retained by that workflow run rather than submitting the Maven artifacts again.
