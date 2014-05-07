# FABA = Fast Bytecode Analysis

## Quick Start

Infer `@NotNull` annotations for `jarFile` and write result to `outFile`

    $ sbt
    > runMain faba.NotNullParametersProcessor jarFile outFile

Example run:

    $ sbt
    > runMain faba.NotNullParametersProcessor /Library/Java/JavaVirtualMachines/jdk1.7.0_45.jdk/Contents/Home/jre/lib/rt.jar out.txt

Infer `@Contact(_, null, _) -> false`, `@Contact(_, null, _) -> true` annotations and write result to `outFile`

    $ sbt
    > runMain faba.NullBooleanContractsProcessor jarFile outFile

Example run:

    $ sbt
    > runMain faba.NullBooleanContractsProcessor /Library/Java/JavaVirtualMachines/jdk1.7.0_45.jdk/Contents/Home/jre/lib/rt.jar out.txt
