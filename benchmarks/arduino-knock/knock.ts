@external("env", "chip_delay") declare function chip_delay(value: i32): void;
@external("env", "chip_pin_mode") declare function chip_pin_mode(pin: i32, mode: i32): void;
@external("env", "chip_digital_write") declare function chip_digital_write(pin: i32, value: i32): void;
@external("env", "chip_analog_read") declare function chip_analog_read(pin: i32): i32;
@external("env", "print_string") export declare function print_string(text: ArrayBuffer, length: u32): void;

function print(text: string): void {
    //print_string(String.UTF8.encode(text, true), String.UTF8.byteLength(text, true));
}

const INPUT = 0;
const OUTPUT = 1;

enum Pin {
    ledPin = 13,
    knockSensor = 0,
}

const threshold: i32 = 100;

let sensorReading: i32 = 0;
let ledState: i32 = 0;

export function main(): void {
    chip_pin_mode(Pin.ledPin, OUTPUT);

    while (true) {
        sensorReading = chip_analog_read(Pin.knockSensor);

        if (sensorReading >= threshold) {
            ledState = !ledState;
            chip_digital_write(Pin.ledPin, ledState);
            print("Knock!\n");
            chip_delay(100);
        }
    }
}
