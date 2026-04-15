@external("env", "chip_delay") declare function chip_delay(value: i32): void;
@external("env", "chip_pin_mode") declare function chip_pin_mode(pin: i32, mode: i32): void;
@external("env", "chip_digital_write") declare function chip_digital_write(pin: i32, value: i32): void;
@external("env", "chip_analog_read") declare function chip_analog_read(pin: i32): i32;
/*@external("env", "print_string") export declare function print_string(text: ArrayBuffer, length: u32): void;

function print(text: string): void {
    print_string(String.UTF8.encode(text, true), String.UTF8.byteLength(text, true));
}*/

const INPUT = 0;
const OUTPUT = 1;

enum Pin {
    sensorPin = 0,
    ledPin2 = 2,
    ledPin3 = 3,
    ledPin4 = 4,
}

const baselineTemp: f32 = 20.0;

export function main(): void {
    for (let pinNumber = Pin.ledPin2; pinNumber <= Pin.ledPin4; pinNumber++) {
        chip_pin_mode(pinNumber, OUTPUT);
        chip_digital_write(pinNumber, 0);
    }

    while (true) {
        let sensorVal: i32 = chip_analog_read(Pin.sensorPin);
        //print("sensor Value: " + sensorVal.toString());

        let voltage: f32 = (sensorVal as f32 / 1024.0 as f32) * 5.0;
        //print(", Volts: " + voltage.toString());

        let temperature: f32 = (voltage - 0.5) * 100;
        //print(", degrees C: " + temperature.toString() + "\n");

        if (temperature < baselineTemp + 2) {
            chip_digital_write(Pin.ledPin2, 0);
            chip_digital_write(Pin.ledPin3, 0);
            chip_digital_write(Pin.ledPin4, 0);
        } else if (temperature >= baselineTemp + 2 && temperature < baselineTemp + 4) {
            chip_digital_write(Pin.ledPin2, 1);
            chip_digital_write(Pin.ledPin3, 0);
            chip_digital_write(Pin.ledPin4, 0);
        } else if (temperature >= baselineTemp + 4 && temperature < baselineTemp + 6) {
            chip_digital_write(Pin.ledPin2, 1);
            chip_digital_write(Pin.ledPin3, 1);
            chip_digital_write(Pin.ledPin4, 0);
        } else if (temperature >= baselineTemp + 6) {
            chip_digital_write(Pin.ledPin2, 1);
            chip_digital_write(Pin.ledPin3, 1);
            chip_digital_write(Pin.ledPin4, 1);
        }

        chip_delay(1);
    }
}
