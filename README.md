# MIO Debugger

[![DOI](https://zenodo.org/badge/893997044.svg)](https://doi.org/10.5281/zenodo.15758201)

The MIO _/maː.joː/_ debugger is a first of its kind multiverse debugger designed for debugging WebAssembly programs on the WARDuino virtual machine.

## Dependencies
To build MIO + WARDuino you will need the following dependencies:

- Java 21
- A modern C++ compiler
- Cmake
- Z3 for WARDuino's concolic mode

## Getting started
> [!NOTE]
> Currently only Linux and macOS are supported.

MIO uses the Gradle build system. To get started, `./gradlew setup` can be used. This will build MIO and WARDuino and create a default configuration file in `~/.mio/debugger.properties`. 

The debugger can then be started by running `java -jar mio.jar` in the `build/libs` directory. To run MIO you will need Java 22 or higher.

```bash
git clone --recursive git@github.com:TOPLLab/MIO.git
cd MIO
./gradlew setup
cd build/libs
java -jar mio.jar
```

More experienced users can also just build MIO by itself by using `./gradlew fatjar`. When doing so you will need to manually create a configuration file and provide or build your own copy of WARDuino. More information about the configuration file can be found below.

## Configuration
To use the debugger you will need a `debugger.properties` configuration file, this config currently needs to be positioned at
`~/.mio/debugger.properties`. This config specifies where the debugger can find WARDuino, which USB port it should
use, if the debugger should run on an emulator or not and how big the UI should be. An example of this config file can
be found [here](debugger.properties).

> [!NOTE]
> The concolic option currently requires the usage of a modified WARDuino version which can be found on the `feat/symbolic-templated` branch. This branch houses the code for the concolic execution engine used for suggesting interesting paths.

## Command line arguments
MIO has various command line arguments that can be used to flash and debug programs. We list these options here:
- `repl` which starts up a debugger repl.
- `debug` which starts up the graphical debugger, this option requires two arguments a `.wasm.map` and `.wasm` file. Example: `debug robotarm.wasm.map robotarm.wasm`.
- `flash` which uploads the program provided as an argument and starts to run it on the microcontroller.
- `run` same thing as flash but after uploading the module it will open a debugger repl.

If no option is specified, MIO will open a welcome screen allowing you to select the program you would like to debug using a graphical interface. This graphical window works similarly to the `debug` option.
