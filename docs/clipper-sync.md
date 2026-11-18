# Android clipper asset updates

The `Sync Android clipper assets` GitHub Actions workflow runs weekly and can
also be started manually from the Actions tab. Each run clones the default
branch of `iamtalker/joplin-clipper-plus`, generates the phone-compatible site
rules from its `content.js`, and syncs the three existing clipping libraries.
It can commit only these four files under `app/src/main/assets/clip/`:

- `site-rules.json`
- `Readability.js`
- `turndown.js`
- `turndown-plugin-gfm.js`

The rules generator checks that every source table and library exists and that
the generated rules have the Android schema before writing any asset. It keeps
the current `updated` date when the tables are unchanged, so no-op runs do not
create file churn. When rules change, it advances that date. The workflow uses
the repository's built-in `GITHUB_TOKEN`, limited to repository contents, and
serializes runs that write to `main`.

Android already refreshes `site-rules.json` from its public raw GitHub URL about
once a week. Updated rules therefore reach installed app versions without an
APK release. The three JavaScript libraries are bundled into the app, so library
changes take effect only after a new Android app build is released and
installed. The app continues to execute only bundled JavaScript; the refresh
downloads rules data, not executable code.

To run the sync locally, provide a checkout of the upstream default branch:

```sh
python3 tools/sync_clipper_android.py /path/to/joplin-clipper-plus
```

Pass a second directory to exercise the generator against a temporary asset
copy without changing the repository bundle.
