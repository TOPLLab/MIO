@external("env", "chip_delay") declare function chip_delay(value: i32): void;
@external("env", "chip_pin_mode") declare function chip_pin_mode(pin: i32, mode: i32): void;
@external("env", "chip_digital_write") declare function chip_digital_write(pin: i32, value: i32): void;
@external("env", "chip_digital_read") declare function chip_digital_read(pin: i32): i32;
@external("env", "chip_analog_read") declare function chip_analog_read(pin: i32): i32;
@external("env", "chip_analog_write") declare function chip_analog_write(pin: i32, value: i32): void;

const INPUT = 0;
const OUTPUT = 2;

enum Pin {
    sensorPin = 0,
    ledPin = 9,
    indicatorLedPin = 13,
    buttonPin = 2,
}

let sensorMin: i32 = 1023;  // minimum sensor value
let sensorMax: i32 = 2048;     // maximum sensor value
let sensorValue: i32 = 0;   // the sensor value

function calibrate(): void {
    // turn on the indicator LED to indicate that calibration is happening:
    chip_digital_write(Pin.indicatorLedPin, 1);
    // read the sensor:
    sensorValue = chip_analog_read(Pin.sensorPin);

    // record the maximum sensor value
    if (sensorValue > sensorMax) {
        sensorMax = sensorValue;
    }

    // record the minimum sensor value
    if (sensorValue < sensorMin) {
        sensorMin = sensorValue;
    }
}

export function main(): void {
    chip_pin_mode(Pin.indicatorLedPin, OUTPUT);
    chip_pin_mode(Pin.ledPin, OUTPUT);
    chip_pin_mode(Pin.buttonPin, INPUT);

    // while (true) {
    if (true) {
        // while the button is pressed, take calibration readings:
        //while (chip_digital_read(Pin.buttonPin) == 1) {
        /*while (chip_digital_read(Pin.buttonPin) == 1) {
            calibrate();
        }*/
        // signal the end of the calibration period
        chip_digital_write(Pin.indicatorLedPin, 0);

        // read the sensor:
        sensorValue = chip_analog_read(Pin.sensorPin);

        // apply the calibration to the sensor reading
        sensorValue = (sensorValue - sensorMin) * 255 / (sensorMax - sensorMin);

        // in case the sensor value is outside the range seen during calibration
        if (sensorValue < 0) sensorValue = 0;
        if (sensorValue > 255) sensorValue = 255;

        // fade the LED using the calibrated value:
        chip_analog_write(Pin.ledPin, sensorValue);
    }
}

