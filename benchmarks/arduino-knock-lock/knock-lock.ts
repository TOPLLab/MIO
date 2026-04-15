// https://github.com/arduino/arduino-examples/blob/main/examples/10.StarterKit_BasicKit/p12_KnockLock/p12_KnockLock.ino
@external("env", "chip_delay") declare function chip_delay(value: i32): void;
@external("env", "chip_pin_mode") declare function chip_pin_mode(pin: i32, mode: i32): void;
@external("env", "chip_digital_write") declare function chip_digital_write(pin: i32, value: i32): void;
@external("env", "chip_digital_read") declare function chip_digital_read(pin: i32): i32;
@external("env", "chip_analog_read") declare function chip_analog_read(pin: i32): i32;
@external("env", "chip_servo_attach") declare function chip_servo_attach(pin: i32): void;
@external("env", "chip_servo_write") declare function chip_servo_write(pin: i32, value: i32): void;
@external("env", "print_string") export declare function print_string(text: ArrayBuffer, length: u32): void;

function print(text: string): void {
    //print_string(String.UTF8.encode(text, true), String.UTF8.byteLength(text, true));
}

const INPUT = 0;
const OUTPUT = 1;

enum Pin {
    piezo = 0,
    switchPin = 2,
    yellowLed = 3,
    greenLed = 4,
    redLed = 5,
    servoPin = 9,
}

let knockVal: i32;
let switchVal: i32;
const quietKnock: i32 = 10;
const loudKnock: i32 = 100;
let locked: bool = false;
let numberOfKnocks: i32 = 0;

function checkForKnock(value: i32): bool {
    if (value > quietKnock && value < loudKnock) {
        chip_digital_write(Pin.yellowLed, 1);
        chip_delay(50);
        chip_digital_write(Pin.yellowLed, 0);
        //print("Valid knock of value " + value.toString() + "\n");
        return true;
    } else {
        //print("Bad knock value " + value.toString() + "\n");
        return false;
    }
}

export function main(): void {
    //chip_servo_attach(Pin.servoPin);
    chip_pin_mode(Pin.yellowLed, OUTPUT);
    chip_pin_mode(Pin.redLed, OUTPUT);
    chip_pin_mode(Pin.greenLed, OUTPUT);
    chip_pin_mode(Pin.switchPin, INPUT);

    chip_digital_write(Pin.greenLed, 1);
    //chip_servo_write(Pin.servoPin, 0);
    //print("the box is unlocked!\n");

    //while (true) {
    // Run the test for two loop iterations.
    for (let i = 0; i < 2; i++) {
        if (!locked) {
            switchVal = chip_digital_read(Pin.switchPin);
            if (switchVal == 1) {
                locked = true;
                chip_digital_write(Pin.greenLed, 0);
                chip_digital_write(Pin.redLed, 1);
                //chip_servo_write(Pin.servoPin, 90);
                //print("the box is locked!\n");
                chip_delay(1000);
            }
        }

        if (locked) {
            knockVal = chip_analog_read(Pin.piezo);
            if (numberOfKnocks < 3 && knockVal > 0) {
                if (checkForKnock(knockVal)) {
                    numberOfKnocks++;
                }
                //print((3 - numberOfKnocks).toString() + " more knocks to go\n");
            }

            if (numberOfKnocks >= 3) {
                locked = false;
                //chip_servo_write(Pin.servoPin, 0);
                chip_delay(20);
                chip_digital_write(Pin.greenLed, 1);
                chip_digital_write(Pin.redLed, 0);
                //print("the box is unlocked!\n");
                numberOfKnocks = 0;
            }
        }
    }
}
