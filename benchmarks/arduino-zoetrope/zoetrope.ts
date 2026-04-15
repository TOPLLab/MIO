@external("env", "chip_delay") declare function chip_delay(value: i32): void;
@external("env", "chip_pin_mode") declare function chip_pin_mode(pin: i32, mode: i32): void;
@external("env", "chip_digital_write") declare function chip_digital_write(pin: i32, value: i32): void;
@external("env", "chip_digital_read") declare function chip_digital_read(pin: i32): i32;
@external("env", "chip_analog_read") declare function chip_analog_read(pin: i32): i32;
@external("env", "chip_analog_write") declare function chip_analog_write(pin: i32, value: i32): void;

const INPUT = 0;
const OUTPUT = 1;

enum Pin {
    controlPin1 = 2,
    controlPin2 = 3,
    enablePin = 9,
    directionSwitchPin = 4,
    onOffSwitchStateSwitchPin = 5,
    potPin = 0,
}

let onOffSwitchState: i32 = 0;
let previousOnOffSwitchState: i32 = 0;
let directionSwitchState: i32 = 0;
let previousDirectionSwitchState: i32 = 0;
let motorEnabled: i32 = 0;
let motorSpeed: i32 = 0;
let motorDirection: i32 = 1;

export function main(): void {
    chip_pin_mode(Pin.directionSwitchPin, INPUT);
    chip_pin_mode(Pin.onOffSwitchStateSwitchPin, INPUT);
    chip_pin_mode(Pin.controlPin1, OUTPUT);
    chip_pin_mode(Pin.controlPin2, OUTPUT);
    chip_pin_mode(Pin.enablePin, OUTPUT);

    chip_digital_write(Pin.enablePin, 0);

    //while (true) {
    for (let i = 0; i < 2; i++) {
        onOffSwitchState = chip_digital_read(Pin.onOffSwitchStateSwitchPin);
        chip_delay(1);

        directionSwitchState = chip_digital_read(Pin.directionSwitchPin);
        motorSpeed = chip_analog_read(Pin.potPin) / 4;

        if (onOffSwitchState != previousOnOffSwitchState) {
            if (onOffSwitchState == 1) {
                motorEnabled = !motorEnabled;
            }
        }

        if (directionSwitchState != previousDirectionSwitchState) {
            if (directionSwitchState == 1) {
                motorDirection = !motorDirection;
            }
        }

        if (motorDirection == 1) {
            chip_digital_write(Pin.controlPin1, 1);
            chip_digital_write(Pin.controlPin2, 0);
        } else {
            chip_digital_write(Pin.controlPin1, 0);
            chip_digital_write(Pin.controlPin2, 1);
        }

        if (motorEnabled == 1) {
            chip_analog_write(Pin.enablePin, motorSpeed);
        } else {
            chip_analog_write(Pin.enablePin, 0);
        }

        previousDirectionSwitchState = directionSwitchState;
        previousOnOffSwitchState = onOffSwitchState;
    }
}
