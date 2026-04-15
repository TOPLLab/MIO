@external("env", "chip_delay") declare function chip_delay(value: i32): void;
@external("env", "chip_pin_mode") declare function chip_pin_mode(pin: i32, mode: i32): void;
@external("env", "chip_digital_write") declare function chip_digital_write(pin: i32, value: i32): void;
@external("env", "chip_digital_read") declare function chip_digital_read(pin: i32): i32;
@external("env", "chip_analog_read") declare function chip_analog_read(pin: i32): i32;
//@external("env", "chip_tone") declare function chip_tone(pin: i32, frequency: i32): void;
//@external("env", "chip_noTone") declare function chip_noTone(pin: i32): void;
@external("env", "print_string") export declare function print_string(text: ArrayBuffer, length: u32): void;

function print(text: string): void {
    //print_string(String.UTF8.encode(text, true), String.UTF8.byteLength(text, true));
}

function chip_tone(pin: i32, note: i32): void {

}

function chip_noTone(pin: i32): void {

}

const INPUT = 0;
const OUTPUT = 1;

enum Pin {
    potPin = 0,
    tonePin = 8,
}

let notes: i32[] = [262, 294, 330, 349];

export function main(): void {
    let keyVal: i32;

    while (true) {
        keyVal = chip_analog_read(Pin.potPin);
        //print(keyVal.toString() + "\n");

        if (keyVal == 1023) {
            chip_tone(Pin.tonePin, notes[0]);
        } else if (keyVal >= 990 && keyVal <= 1010) {
            chip_tone(Pin.tonePin, notes[1]);
        } else if (keyVal >= 505 && keyVal <= 515) {
            chip_tone(Pin.tonePin, notes[2]);
        } else if (keyVal >= 5 && keyVal <= 10) {
            chip_tone(Pin.tonePin, notes[3]);
        } else {
            chip_noTone(Pin.tonePin);
        }
    }
}
