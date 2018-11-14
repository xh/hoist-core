## v5.2.0

### ⚙️ Technical

Processing of uploaded grid data for Excel export modified to handle even larger export sets. 
⚠️ Note requires client-side toolkit updates (>= v16 for Hoist React).

:octocat: [Commit Log](https://github.com/exhi/hoist-core/compare/v5.1.0...v5.2.0)

## v5.1.0

### 🎁 New Features
* `InstanceConfigUtils` can now read bootstrap configuration values from a directory of files, each containing a single config, in addition to the previous support for yaml files. This supports reading low-level configuration from Docker/Kubernetes configs and secrets, which are mounted to the container filesystem as a directory.

### 🐞 Bug Fixes
* An admin client that happens to be polling for a non-existent log file (e.g. one that has just been archived) will no longer spam the logs with stracktraces.

:octocat: [Commit Log](https://github.com/exhi/hoist-core/compare/v5.0.4...v5.1.0)

## v5.0.4

### 🐞 Bug Fixes
* Avoids the use of (sometimes) reserved SQL word `level` in the `LogLevel` config object. Remapped to `log_level` column. 

⚠️ Note that this will require a schema update if Grails is not configured to do so automatically, and will result in existing overrides having a null level (which is supported and means "no override").

:octocat: [Commit Log](https://github.com/exhi/hoist-core/compare/release-5.0.3...v5.0.4)

## v5.0.3

### 💥 Breaking Changes
**This release includes several core API changes to how users and their roles (permissions) are loaded.** (Note that the breaking changes below will typically be handled by updates to a custom enterprise plugin and not require individual app changes.)
* Applications (or enterprise plugins) must implement a new `RoleService` extending from `BaseRoleService` to provide a map of users to their app-specific roles. Roles continue to be modelled as simple strings for use both on server and client.
* The `HoistUser` superclass no longer holds / caches its roles directly, but instead calls into the new required `RoleService` dynamically when asked. 
* Boilerplate around auth whitelists and resources has been better consolidated into the plugin, helping to clean up some repeated application-level `AuthenticationService` code. 
* Hoist implementation endpoints have moved from `/hoistImpl/ -> /xh/` for brevity / clarity. Client-side plugins will be updated to use this new path. The implementation APIs used to login/logout and confirm auth / roles have changed, but again are handled by Hoist client plugin updates and do not require application-level changes.

:octocat: [Commit Log](https://github.com/exhi/hoist-core/compare/release-4.3.0...release-5.0.3)

## v4.3.0

### 🎁 New Features
* Server-side Excel export supports `LONG_TEXT` format with wrapped multi-line cells.

### 🐞 Bug Fixes
* Objects extending `JSONFormat` with `cacheJSON` enabled can be rendered at the top-level of a return.

:octocat: [Commit Log](https://github.com/exhi/hoist-core/compare/release-4.2.1...release-4.3.0)

## v4.2.1

* Added support for `activeOnly` argument to `UserAdminController` - required for exhi/hoist-react#567

:octocat: [Commit Log](https://github.com/exhi/hoist-core/compare/release-4.2.0...release-4.2.1)

## v4.2.0

### 🎁  New Features
* Added support for PUT, PATCH, DELETE to BaseProxyService.

:octocat: [Commit Log](https://github.com/exhi/hoist-core/compare/release-4.1.0...release-4.2.0)

## v4.1.0

### 📚 Libraries
* Grails `3.3.5 -> 3.3.8`

:octocat: [Commit Log](https://github.com/exhi/hoist-core/compare/release-4.0.0...release-4.1.0)

## v4.0.0

### 💥 Breaking Changes

* Relevant `HoistImplController` endpoints now require a `clientUsername` param to ensure the client and server are in sync re. the currently active user. This resolves edge-case bugs around impersonation and preference, dashboard changes flushing changes on unload to the wrong server-side user. (#46)

:octocat: [Commit Log](https://github.com/exhi/hoist-core/compare/release-3.1.2...release-4.0.0)

## v3.1.2

### 🐞 Bug Fixes

* The `xhEmailDefaultDomain` config no longer needs a leading (and confusing) `@` - EmailService will prepend this automatically.
  * ⚠️ Note app-level configs with a leading `@` in place will need to be adjusted. (#43)

:octocat: [Commit Log](https://github.com/exhi/hoist-core/compare/release-3.1.1...release-3.1.2)

## v3.1.1

### 🐞 Bug Fixes

+ IdentityService.getUser() should not throw when called outside context of a request -- just return null. Important when e.g. looking for a username within service calls that might be triggered by a controller-based web request or a timer-based thread. 4130a9add8dd8ba22376ea69cfa3a3d095bdf6b0

:octocat: [Commit Log](https://github.com/exhi/hoist-core/compare/release-3.1.0...release-3.1.1)

## v3.1.0

### 🎁 New Features

* Group field added to Preferences for better organization and consistency with AppConfigs.<br><br> ⚠️ **Note** schema update required:
```sql
--MySQL
ALTER TABLE xh_preference ADD group_name VARCHAR(255);
UPDATE xh_preference SET group_name = 'Default' WHERE group_name IS NULL;
ALTER TABLE xh_preference MODIFY group_name VARCHAR(255) NOT NULL;
```

```sql
--SQL Server
ALTER TABLE xh_preference ADD group_name VARCHAR(255);
UPDATE xh_preference SET group_name = 'Default' WHERE group_name IS NULL;
ALTER TABLE xh_preference ALTER COLUMN group_name varchar(255) NOT NULL
```


* ClientError tracking gets a `userAlerted` flag to record whether or not the user was shown a pop-up dialog (vs. an error being reported quietly in the background).<br><br> ⚠️ **Note** schema update required:
```sql
-- SQL Server
ALTER TABLE xh_client_error ADD user_alerted bit NOT NULL DEFAULT 0
-- MySQL
ALTER TABLE xh_client_error ADD user_alerted bit(1) NOT NULL DEFAULT 0
```

### 🐞 Bug Fixes

* Log archiving fixed for apps with a dash or underscore in their appCode.

## v.3.0.4

### 🐞 Bugfixes
* Removed plugin grails-x-frame-options-plugin.  It will be put into the hoist-sencha project.  
It is only needed in hoist-sencha apps.  Hoist-react apps will get this header set by nginx.

## v.3.0.3

### 🎁 New Features
* Added plugin [grails-x-frame-options-plugin](https://github.com/mrhaki/grails-x-frame-options-plugin)
* This prevents XSS attacks by setting by default the most strict header setting `X-Frame-Options: DENY` on all responses from the grails server. You can relax this strict setting to `SAMEORIGIN` (and will probably want to) by adding `plugin.xframeoptions.sameOrigin = true` inside the grails clause of your application.groovy file  (see piq-react for example).

## v3.0.2

### 📚 Libraries

* Gradle wrapper `4.8`

## v3.0.1

### 🎁 New Features

* Updates of following libraries:
```
grailsVersion=3.3.1 -> 3.3.5
grailsAsyncVersion=3.3.1 -> 3.3.2
gormVersion=6.1.7.RELEASE -> 6.1.9.RELEASE
```
* Note Grails update fixes support for the pathJar which helps fix long class path issues on Windows.
* Default theme is now the 'light' theme.

## v3.0.0

### 💥 Breaking Changes

* This release unwinds the multi environment config concept. See #30 for the corresponding issue.
* To take this update, developers need to also migrate to v5.X.X of hoist-react or v2.X.X of hoist-sencha and follow these steps in each environment:

#### Step 1
If you are doing this migration in a lower environment (dev, stage, uat) you may want to keep that environment's configs.  For example, if you are migrating the dev env app to this new code, and there are configs in the dev_value column that you would like to keep in the dev environment, you first need to manually copy these values from the dev field to the prod field in the dev admin config UI.

#### Step 2
Turn off your grails server and your webpack server (if applicable).
Add a new 'value' column with allow null, allow null in the old 'prod_value' column, then copy prod_value values over to the new value column:
##### For MySQL DB:
```
ALTER TABLE `xh_config` ADD `value` LONGTEXT; 
ALTER TABLE `xh_config` MODIFY `prod_value` LONGTEXT;
```

##### For MS SQL Server DB:
```
ALTER TABLE xh_config 
ADD value varchar(max) NULL
ALTER COLUMN prod_value varchar(max)
```

##### For MySQL DB:
```
UPDATE `xh_config` SET `value` = `prod_value`
```

##### For MS SQL Server DB:
```
UPDATE xh_config SET value = prod_value
```


#### Step 3
Update app code in environment to use hoist-core v3.0.0 and hoist-react v5.X.X or hoist-sencha v2.0.0. 
If your app is in a customer/client's environment, you will not be updating hoist-core, but rather [customer]HoistVersion to version 5.0.0.

Remove
```
supportedEnvironments = ['Staging', 'Development']
```
from grails-app/conf/application.groovy in your app.  (Note that this line might appear twice - once near the top if an app has customized and once in the "hoistDefaults" section.)



#### Step 4
Set value to not accept NULL and drop old columns:

##### For MySQL DB:
```
ALTER TABLE `xh_config` 
  MODIFY `value` LONGTEXT NOT NULL;

ALTER TABLE `xh_config` 
  DROP COLUMN `beta_value`, `stage_value`, `dev_value`, `prod_value`;
```

##### For MS SQL Server DB:
```
ALTER TABLE xh_config
  ALTER COLUMN value varchar(max) NOT NULL

ALTER TABLE xh_config
  DROP COLUMN prod_value, dev_value, stage_value, beta_value

```


### 🎁 New Features

* None

### 🐞 Bugfixes

* None

## v2.0.0

### 💥 Breaking Changes

* This release includes updates around how the key `appCode` and `appName` identifiers are read from application builds and what they represent.  See #33 for the corresponding issue. This standardizes the meaning of these two identifiers on the client and server, and decouples the server-side appCode from the Gradle project name.
* To take this update, applications must ensure their `build.gradle` file populates these variables within a grails.build.info file created by Grails itself during the build. See e.g. [this commit to the Toolbox app](https://github.com/exhi/toolbox/commit/073376eefba37ea0ffec073478bb628be447c77b) for an example of this change.
* Apps should audit their use of `Utils.appName` on the server-side and update to `Utils.appCode` if they need to continue accessing the shortname variant.

### 🎁 New Features

* None

### 🐞 Bugfixes

* None

## v1.2.0

### 💥 Breaking Changes

* None

### 🎁 New Features

This release adds support for [InstanceConfigUtils](https://github.com/exhi/hoist-core/blob/1fa43564fb77e11a04dd075d969b300f38252579/src/main/groovy/io/xh/hoist/util/InstanceConfigUtils.groovy), a utility for loading configuration properties from an external YAML file once on startup and exposing them to the application as a map.
* These are intended to be minimal, low-level configs that apply to a particular deployed instance of the application and therefore are better sourced from a local file/volume vs. source code, JavaOpts, or database-driven ConfigService entries.
* Examples include the AppEnvironment as well as common Bootstrap requirements such as database credentials.
* See the class-level doc comment for additional details. Use of InstanceUtils is _not_ required to take this release.

### 🐞 Bugfixes

* Fix NPE breaking FeedbackService emailing. 8f07caf677dc0ed3a5ae6c8dd99dc59e2ffd8508
* Make LogLevel adjustments synchronous, so they reflect immediately in Admin console UI. dc387e885bea14b0443d5e984ccd74238fa6e7b7
