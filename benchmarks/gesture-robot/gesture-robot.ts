@external("env", "chip_delay") declare function chip_delay(value: i32): void;
@external("env", "chip_pin_mode") declare function chip_pin_mode(pin: i32, mode: i32): void;
@external("env", "chip_digital_write") declare function chip_digital_write(pin: i32, value: i32): void;
@external("env", "chip_analog_read") declare function chip_analog_read(pin: i32): i32;
@external("env", "print_string") export declare function print_string(text: ArrayBuffer, length: u32): void;

function print(text: string): void {
    print_string(String.UTF8.encode(text, true), String.UTF8.byteLength(text, true));
}

const INPUT = 0;
const OUTPUT = 1;

enum Pin {
    ap1 = 0,
    ap2 = 1,
    pin13 = 13,
    pin12 = 12,
    pin11 = 11,
    pin10 = 10,
}

let sv1: i32 = 0;
let ov1: i32 = 0;
let sv2: i32 = 0;
let ov2: i32 = 0;

function map(value: i32, fromLow: i32, fromHigh: i32, toLow: i32, toHigh: i32): i32 {
    return (value - fromLow) * (toHigh - toLow) / (fromHigh - fromLow) + toLow;
}

export function main(): void {
    chip_pin_mode(Pin.pin13, OUTPUT);
    chip_pin_mode(Pin.pin12, OUTPUT);
    chip_pin_mode(Pin.pin11, OUTPUT);
    chip_pin_mode(Pin.pin10, OUTPUT);

    //while (true) {
    while (true) {
        sv1 = chip_analog_read(Pin.ap1);
        ov1 = map(sv1, 0, 1023, 0, 255);
        chip_delay(2);
        sv2 = chip_analog_read(Pin.ap2);
        ov2 = map(sv2, 0, 1023, 0, 255);
        chip_delay(2);

        //print("Xsensor1 = " + sv1.toString() + "\t output1 = " + ov1.toString() + "\n");
        //print("Ysensor2 = " + sv2.toString() + "\t output2 = " + ov2.toString() + "\n");

        if (chip_analog_read(Pin.ap1) < 514 && chip_analog_read(Pin.ap2) < 463) {
            // for backward movement
            chip_digital_write(Pin.pin13, 1);
            chip_digital_write(Pin.pin12, 0);
            chip_digital_write(Pin.pin11, 1);
            chip_digital_write(Pin.pin10, 0);
        } else if (chip_analog_read(Pin.ap1) < 486 && chip_analog_read(Pin.ap2) > 508) {
            // for left turn
            chip_digital_write(Pin.pin13, 0);
            chip_digital_write(Pin.pin12, 1);
            chip_digital_write(Pin.pin11, 1);
            chip_digital_write(Pin.pin10, 0);
        } else if (chip_analog_read(Pin.ap1) > 512 && chip_analog_read(Pin.ap2) > 560) {
            // for forward
            chip_digital_write(Pin.pin13, 0);
            chip_digital_write(Pin.pin12, 1);
            chip_digital_write(Pin.pin11, 0);
            chip_digital_write(Pin.pin10, 1);
        } else if (chip_analog_read(Pin.ap1) > 550 && chip_analog_read(Pin.ap2) > 512) {
            // for right turn
            chip_digital_write(Pin.pin13, 1);
            chip_digital_write(Pin.pin12, 0);
            chip_digital_write(Pin.pin11, 0);
            chip_digital_write(Pin.pin10, 1);
        } else {
            chip_digital_write(Pin.pin13, 1);
            chip_digital_write(Pin.pin12, 1);
            chip_digital_write(Pin.pin11, 1);
            chip_digital_write(Pin.pin10, 1);
        }
        // Stop after the first loop iteration, limiting the variable count is not enough for this program.
        return
    }
}
