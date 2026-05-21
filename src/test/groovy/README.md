# hoist-core unit tests

Spock-based unit tests for pure-JDK classes in hoist-core. First introduced alongside the v41
jasypt removal to cover the new `io.xh.hoist.security.*` crypto utilities.

## Running

From the repo root:

```bash
./gradlew test                        # all unit tests
./gradlew test --tests <FullyQualifiedSpecName>   # one spec
```

CI runs `./gradlew clean build`, which includes `test`.

## Adding a spec

- Mirror the package path of the class under test (e.g.
  `src/main/groovy/io/xh/hoist/foo/Bar.groovy` → `src/test/groovy/io/xh/hoist/foo/BarSpec.groovy`).
- Name the file `<ClassUnderTest>Spec.groovy`.
- Extend `spock.lang.Specification`.
- Use `where:` blocks for table-driven cases — they produce clearer failure output than asserting
  inside a loop.

## Scope

These specs target **pure-JDK code** — classes with no dependency on a Grails application
context, the test database, Hazelcast, or `BootStrap`. Examples that fit:

- Crypto / encoding utilities.
- Pure data transformations and parsers.
- Static helpers in `src/main/groovy/io/xh/hoist/util/`.

Anything that needs Grails wiring (services, controllers, GORM, clustering) does **not** belong
here. There is no integration-test harness in hoist-core yet — for now, integration coverage
lives downstream in consuming apps' own Grails integration suites (e.g. Toolbox).

## Fixture data — jasypt 1.9.3 legacy values

`LegacyJasyptDecrypterSpec` and `HoistPasswordEncoderSpec` pin known ciphertexts and password
digests produced by jasypt 1.9.3 itself. Jasypt is no longer on the project's classpath, so
fixtures are regenerated out-of-band on a standalone JVM (jasypt 1.9.3 runs fine outside Spring
Boot's launcher classloader). The reusable generator script:

```groovy
@Grab(group='org.jasypt', module='jasypt', version='1.9.3')
import org.jasypt.util.text.BasicTextEncryptor
import org.jasypt.util.password.BasicPasswordEncryptor

def pwd = 'dsd899s_*)jsk9dsl2fd223hpdj32))I@333'  // AppConfig obfuscation key
def textEnc = new BasicTextEncryptor(); textEnc.setPassword(pwd)
def pwdEnc = new BasicPasswordEncryptor()

println textEnc.encrypt('hello world')          // ciphertext for the AppConfig pwd path
println pwdEnc.encryptPassword('secret')        // hash for the local-user-password path
```

Save as `GenerateFixtures.groovy` and run with `groovy GenerateFixtures.groovy`.

For NFC vs NFD coverage, construct the two forms via codepoints (not source literals — IDEs
silently normalize):

```groovy
def cafeNFC = 'caf' + new String([0x00E9] as int[], 0, 1)               // U+00E9
def cafeNFD = 'caf' + new String([0x0065, 0x0301] as int[], 0, 2)      // 'e' + combining acute
```
