@external("env", "chip_analog_read") declare function chip_analog_read(pin: i32): i32;
@external("env", "chip_digital_write") declare function chip_digital_write(pin: i32, value: i32): void;
@external("env", "chip_pin_mode") declare function chip_pin_mode(pin: i32, mode: i32): void;

enum Pin {
    Red = 15,
    Orange = 16,
    Green = 17,
    Sensor = 12
}

enum PinMode {
    Input = 0,
    Output = 2
}

const highThreshold = 70;
const warningThreshold = 50;

function readTemp(pin: Pin): i32 {
    return (chip_analog_read(Pin.Sensor) * 488 - 50000) / 1000;
}

function setColor(pin: Pin): void {
    chip_digital_write(Pin.Orange, pin == Pin.Orange);
    chip_digital_write(Pin.Red, pin == Pin.Red);
    chip_digital_write(Pin.Green, pin == Pin.Green);  
}

export function main(): void {
    chip_pin_mode(Pin.Sensor, PinMode.Input);
    chip_pin_mode(Pin.Red, PinMode.Output);
    chip_pin_mode(Pin.Orange, PinMode.Output);
    chip_pin_mode(Pin.Green, PinMode.Output);

    while (true) {
        if (readTemp(Pin.Sensor) > highThreshold) {
            setColor(Pin.Red);
        }
        else if(readTemp(Pin.Sensor) > warningThreshold) {
            setColor(Pin.Orange);
        }
        else {
            setColor(Pin.Green);
        }
    }
}
