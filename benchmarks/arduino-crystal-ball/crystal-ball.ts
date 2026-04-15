@external("env", "chip_delay") declare function chip_delay(value: i32): void;
@external("env", "chip_pin_mode") declare function chip_pin_mode(pin: i32, mode: i32): void;
@external("env", "chip_digital_write") declare function chip_digital_write(pin: i32, value: i32): void;
@external("env", "chip_digital_read") declare function chip_digital_read(pin: i32): i32;
@external("env", "lcd_begin") declare function lcd_begin(cols: i32, rows: i32): void;
@external("env", "lcd_setCursor") declare function lcd_setCursor(col: i32, row: i32): void;
@external("env", "lcd_print") declare function lcd_print(text: string): void;
@external("env", "lcd_clear") declare function lcd_clear(): void;
@external("env", "print_string") export declare function print_string(text: ArrayBuffer, length: u32): void;
@external("env", "chip_analog_read") declare function chip_analog_read(index: i32): i32;

function print(text: string): void {
    //print_string(String.UTF8.encode(text, true), String.UTF8.byteLength(text, true));
}

const INPUT = 0;
const OUTPUT = 1;

enum Pin {
    switchPin = 6,
    lcd_rs = 12,
    lcd_en = 11,
    lcd_d4 = 5,
    lcd_d5 = 4,
    lcd_d6 = 3,
    lcd_d7 = 2,
}

let switchState: i32 = 0;
let prevSwitchState: i32 = 0;
let reply: i32;

function random(max: i32): i32 {
    return chip_analog_read(0) % max;
}

export function main(): void {
    //lcd_begin(16, 2);
    chip_pin_mode(Pin.switchPin, INPUT);

    //lcd_print("Ask the");
    //lcd_setCursor(0, 1);
    //lcd_print("Crystal Ball!");

    //while (true) {
    //if (true) {
    for (let i = 0; i < 2; i++) {
        switchState = chip_digital_read(Pin.switchPin);

        if (switchState != prevSwitchState) {
            if (switchState == 0) { // Was 0 in the original !!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!
                reply = random(8);
                //lcd_clear();
                //lcd_setCursor(0, 0);
                //lcd_print("the ball says:");
                //lcd_setCursor(0, 1);

                switch (reply) {
                    case 0:
                        print("yes");
                        //lcd_print("Yes");
                        break;
                    case 1:
                        print("Most likely");
                        //lcd_print("Most likely");
                        break;
                    case 2:
                        print("Certainly");
                        //lcd_print("Certainly");
                        break;
                    case 3:
                        print("Outlook good");
                        //lcd_print("Outlook good");
                        break;
                    case 4:
                        print("Unsure");
                        //lcd_print("Unsure");
                        break;
                    case 5:
                        print("Ask again");
                        //lcd_print("Ask again");
                        break;
                    case 6:
                        print("Doubtful");
                        //lcd_print("Doubtful");
                        break;
                    case 7:
                        print("No");
                        //lcd_print("No");
                        break;
                }
            }
        }
        prevSwitchState = switchState;
    }
}
