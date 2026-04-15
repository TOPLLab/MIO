@external("env", "chip_delay") declare function chip_delay(value: i32): void;
@external("env", "chip_pin_mode") declare function chip_pin_mode(pin: i32, mode: i32): void;
@external("env", "chip_digital_write") declare function chip_digital_write(pin: i32, value: i32): void;
@external("env", "chip_digital_read") declare function chip_digital_read(pin: i32): i32;
@external("env", "encoder_position") declare function encoder_position(idx: i32): i32;
@external("env", "drive_motor_degrees") declare function drive_motor_degrees(motor_index: i32, angle: i32, speed: i32): void;
@external("env", "setup_uart_sensor") declare function setup_uart_sensor(sensor_index: i32): void;
@external("env", "colour_sensor") declare function colour_sensor(sensor_index: i32): i32;
@external("env", "print_int") declare function print_int(v: i32): void;

const INPUT = 0;
const OUTPUT = 2;

enum Pin { 
    led1 = 10,
    led2 = 11,
    led3 = 12,
    led4 = 13,
    up_button = 21,
    down_button = 20,
}

export function main(): void {
    chip_pin_mode(Pin.led1, OUTPUT)
    chip_pin_mode(Pin.led2, OUTPUT)
    chip_pin_mode(Pin.led3, OUTPUT)
    chip_pin_mode(Pin.led4, OUTPUT)

    chip_pin_mode(Pin.up_button, INPUT)
    chip_pin_mode(Pin.down_button, INPUT)
        
    let i = 0;
    let up_button = 0;
    let down_button = 0;

    while (true) {
        chip_digital_write(Pin.led1, (i >> 3) & 1);
        chip_digital_write(Pin.led2, (i >> 2) & 1);
        chip_digital_write(Pin.led3, (i >> 1) & 1);
        chip_digital_write(Pin.led4, (i >> 0) & 1);
        chip_delay(10);
        let pressed = chip_digital_read(Pin.up_button);
        if (pressed != up_button) {
            up_button = pressed;
            if (pressed) {
                i += 1;
            }
        }
        pressed = chip_digital_read(Pin.down_button);
        if (pressed != down_button) {
            down_button = pressed;
            if (pressed) {
                i -= 1;
            }
        }
        i &= 0xf;
    }
}
