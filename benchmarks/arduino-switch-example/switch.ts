// https://github.com/arduino/arduino-examples/blob/main/examples/05.Control/switchCase/switchCase.ino
@external("env", "chip_delay") declare function chip_delay(value: i32): void;
@external("env", "chip_analog_read") declare function chip_analog_read(pin: i32): i32;
@external("env", "print_string") export declare function print_string(text: ArrayBuffer, length: u32): void;

function print(text: string): void {
    //print_string(String.UTF8.encode(text, true), String.UTF8.byteLength(text, true));
}

const sensorMin: i32 = 0;    // sensor minimum, discovered through experiment
const sensorMax: i32 = 4095;  // sensor maximum, discovered through experiment
enum Pin {
    sensorPin = 0,
}

function map(value: i32, fromLow: i32, fromHigh: i32, toLow: i32, toHigh: i32): i32 {
    return (value - fromLow) * (toHigh - toLow) / (fromHigh - fromLow) + toLow;
}

export function main(): void {
    while (true) {
        // read the sensor:
        let sensorReading: i32 = chip_analog_read(Pin.sensorPin);
        // map the sensor range to a range of four options:
        let range: i32 = map(sensorReading, sensorMin, sensorMax, 0, 3);

        // do something different depending on the range value:
        switch (range) {
            case 0:  // your hand is on the sensor
                print("dark\n");
                break;
            case 1:  // your hand is close to the sensor
                print("dim\n");
                break;
            case 2:  // your hand is a few inches from the sensor
                print("medium\n");
                break;
            case 3:  // your hand is nowhere near the sensor
                print("bright\n");
                break;
        }
        chip_delay(1);  // delay in between reads for stability
    }
}
