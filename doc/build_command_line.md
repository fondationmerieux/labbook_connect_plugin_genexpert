# Build LabBook Connect plugin from command line

Goal: reproduce an **Eclipse Export JAR** for a LabBook Connect plugin from a cloned repository.

Example: `AnalyzerGeneXpert.jar`

Requirements:
- plugin repository cloned
- LabBook Connect project available (for API classes)
- JDK installed
- `java`, `javac` and `jar` available in PATH

## 1) Prepare build directory

```bash
mkdir -p target/classes
```

## 2) Compile Java sources

The plugin requires LabBook Connect classes during compilation.

```bash
javac -cp "lib/*:../labbook_connect/target/classes" -d target/classes $(find src -name "*.java")
```

## 3) Copy resources

Copy plugin resources into compiled classes:

```bash
cp -r resources/* target/classes/
```

## 4) Prepare plugin output directory

```bash
mkdir -p bin/plugin
```

## 5) Generate plugin JAR

Equivalent to Eclipse "Export JAR" with:
- generated class files and resources
- Java source files and resources
- referenced Connect classes
- project resources

```bash
jar cfm bin/plugin/AnalyzerGeneXpert.jar MANIFEST.MF -C ../labbook_connect/target/classes . -C target/classes . src resources doc lib script README.md LICENSE.md CHANGELOG.md MANIFEST.MF .classpath .project
```

## 6) Verify JAR content

```bash
jar tf bin/plugin/AnalyzerGeneXpert.jar | head -20
```

Expected result:

```text
META-INF/
META-INF/MANIFEST.MF
labbook_connect/
plugin/
plugin/Analyzer.class
plugin/Connect_util.class
plugin/AnalyzerGeneXpert.class
```

Check embedded libraries:

```bash
jar tf bin/plugin/AnalyzerGeneXpert.jar | grep "^lib/"
```

Expected result:

```text
lib/hapi-base-2.2.jar
lib/hapi-structures-v251-2.2.jar
lib/logback-classic-1.4.11.jar
lib/logback-core-1.4.11.jar
lib/slf4j-api-2.0.9.jar
lib/toml4j-0.7.2.jar
```

## Result

The generated file:

```text
bin/plugin/AnalyzerGeneXpert.jar
```

is equivalent to the Eclipse JAR export with:
- plugin compiled classes
- LabBook Connect API classes
- resources
- source files
- documentation
- embedded libraries