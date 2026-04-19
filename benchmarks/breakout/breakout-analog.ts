// ST7735 Constants
const ST7735_NOP = 0x0
const ST7735_SWRESET = 0x01
const ST7735_RDDID = 0x04
const ST7735_RDDST = 0x09
const ST7735_SLPIN = 0x10
const ST7735_SLPOUT = 0x11
const ST7735_PTLON = 0x12
const ST7735_NORON = 0x13
const ST7735_INVOFF = 0x20
const ST7735_INVON = 0x21
const ST7735_DISPOFF = 0x28
const ST7735_DISPON = 0x29
const ST7735_CASET = 0x2A
const ST7735_RASET = 0x2B
const ST7735_RAMWR = 0x2C
const ST7735_RAMRD = 0x2E
const ST7735_COLMOD = 0x3A
const ST7735_MADCTL = 0x36
const ST7735_FRMCTR1 = 0xB1
const ST7735_FRMCTR2 = 0xB2
const ST7735_FRMCTR3 = 0xB3
const ST7735_INVCTR = 0xB4
const ST7735_DISSET5 = 0xB6
const ST7735_PWCTR1 = 0xC0
const ST7735_PWCTR2 = 0xC1
const ST7735_PWCTR3 = 0xC2
const ST7735_PWCTR4 = 0xC3
const ST7735_PWCTR5 = 0xC4
const ST7735_VMCTR1 = 0xC5
const ST7735_RDID1 = 0xDA
const ST7735_RDID2 = 0xDB
const ST7735_RDID3 = 0xDC
const ST7735_RDID4 = 0xDD
const ST7735_PWCTR6 = 0xFC
const ST7735_GMCTRP1 = 0xE0
const ST7735_GMCTRN1 = 0xE1
// Constants
const BUTTON = 2
// PIN configurations
const CS = 13
const RESET = 14
const DC = 15
const SDA = 7
const SCK = 6
// Arduino constants
const OUTPUT = 2
const INPUT = 0
const LOW = 0
const HIGH = 1
// Type declarations
@external("env", "write_spi_byte") 
declare function write_spi_byte(byte: i32): void;

@external("env", "write_spi_bytes_16") 
declare function write_spi_bytes_16(times: i32, color: i32): void;

@external("env", "chip_digital_write") 
declare function chip_digital_write(pin: i32, value: i32): void;

@external("env", "chip_digital_read") 
declare function chip_digital_read(pin: i32): i32;

@external("env", "chip_analog_read") 
declare function chip_analog_read(pin: i32): i32;

@external("env", "chip_pin_mode") 
declare function chip_pin_mode(pin: i32, value: i32): void;

@external("env", "chip_delay_us") 
declare function chip_delay_us(value: i32): void;

// Writing LCD pins
function LCD_SCK(b: i32): void { chip_digital_write(SCK, b); }

function LCD_SDO(b: i32): void { chip_digital_write(SDA, b); }

function LCD_RS(b: i32): void { chip_digital_write(DC, b); }

function LCD_CS(b: i32): void { chip_digital_write(CS, b); }

function LCD_RESET(b: i32): void { chip_digital_write(RESET, b); }

function writecommand(c: i32): void {
    LCD_RS(LOW);
    LCD_CS(LOW);
    write_spi_byte(c);
    LCD_CS(HIGH);
}

function writedata(c: i32): void {
    LCD_RS(HIGH);
    LCD_CS(LOW);
    write_spi_byte(c);
    LCD_CS(HIGH);
}

function setAddrWindow(x0: i32, y0: i32, x1: i32, y1: i32): void {
    writecommand(ST7735_CASET);  // column addr set
    writedata(0x00);
    writedata(x0 + 0);  // XSTART
    writedata(0x00);
    writedata(x1 + 0);  // XEND

    writecommand(ST7735_RASET);  // row addr set
    writedata(0x00);
    writedata(y0 + 0);  // YSTART
    writedata(0x00);
    writedata(y1 + 0);  // YEND

    writecommand(ST7735_RAMWR);  // write to RAM
}

const SCREEN_WIDTH = 128
const SCREEN_HEIGHT = 160

function chip_fill_rect(x: i32, y: i32, w: i32, h:i32, color: i32): void {
    setAddrWindow(x, y, x + w - 1, y + h - 1);
    // setup for data
    LCD_RS(HIGH);
    LCD_CS(LOW);
    write_spi_bytes_16(w * h, color);
    LCD_CS(HIGH);
}

function chip_fill_screen(color: i32): void {
    setAddrWindow(0, 0, SCREEN_WIDTH - 1, SCREEN_HEIGHT - 1);
    // setup for data
    LCD_RS(HIGH);
    LCD_CS(LOW);
    write_spi_bytes_16(SCREEN_WIDTH * SCREEN_HEIGHT, color);
    /*unsigned char colorB = color >> 8;
    for (int x=0; x < SCREEN_WIDTH*SCREEN_HEIGHT ; x++) {
            write_spi_byte(colorB);
            write_spi_byte(color);
    }*/
    LCD_CS(HIGH);
}

function ST7735_initR(): void {
    LCD_RESET(HIGH);
    chip_delay_us(500);
    LCD_RESET(LOW);
    chip_delay_us(500);
    LCD_RESET(HIGH);
    chip_delay_us(500);
    LCD_CS(LOW);

    writecommand(ST7735_SWRESET);  // software reset
    chip_delay_us(150);

    writecommand(ST7735_SLPOUT);  // out of sleep mode
    chip_delay_us(500);

    writecommand(ST7735_COLMOD);  // set color mode
    writedata(0x05);              // 16-bit color
    chip_delay_us(10);

    writecommand(ST7735_FRMCTR1);  // frame rate control - normal mode
    writedata(0x01);  // frame rate = fosc / (1 x 2 + 40) * (LINE + 2C + 2D)
    writedata(0x2C);
    writedata(0x2D);

    writecommand(ST7735_FRMCTR2);  // frame rate control - idle mode
    writedata(0x01);  // frame rate = fosc / (1 x 2 + 40) * (LINE + 2C + 2D)
    writedata(0x2C);
    writedata(0x2D);

    writecommand(ST7735_FRMCTR3);  // frame rate control - partial mode
    writedata(0x01);               // dot inversion mode
    writedata(0x2C);
    writedata(0x2D);
    writedata(0x01);  // line inversion mode
    writedata(0x2C);
    writedata(0x2D);

    writecommand(ST7735_INVCTR);  // display inversion control
    writedata(0x07);              // no inversion

    writecommand(ST7735_PWCTR1);  // power control
    writedata(0xA2);
    writedata(0x02);  // -4.6V
    writedata(0x84);  // AUTO mode

    writecommand(ST7735_PWCTR2);  // power control
    writedata(0xC5);              // VGH25 = 2.4C VGSEL = -10 VGH = 3 * AVDD

    writecommand(ST7735_PWCTR3);  // power control
    writedata(0x0A);              // Opamp current small
    writedata(0x00);              // Boost frequency

    writecommand(ST7735_PWCTR4);  // power control
    writedata(0x8A);              // BCLK/2, Opamp current small & Medium low
    writedata(0x2A);

    writecommand(ST7735_PWCTR5);  // power control
    writedata(0x8A);
    writedata(0xEE);

    writecommand(ST7735_VMCTR1);  // power control
    writedata(0x0E);

    writecommand(ST7735_INVOFF);  // don't invert display

    writecommand(ST7735_MADCTL);  // memory access control (directions)

    // http://www.adafruit.com/forums/viewtopic.php?f=47&p=180341

    // R and B byte are swapped
    // madctl = 0xC8;

    // normal R G B order
    // madctl = 0xC0;
    writedata(0xc8);  // row address/col address, bottom to top refresh

    writecommand(ST7735_COLMOD);  // set color mode
    writedata(0x05);              // 16-bit color

    writecommand(ST7735_CASET);  // column addr set
    writedata(0x00);
    writedata(0x00);  // XSTART = 0
    writedata(0x00);
    writedata(0x7F);  // XEND = 127

    writecommand(ST7735_RASET);  // row addr set
    writedata(0x00);
    writedata(0x00);  // XSTART = 0
    writedata(0x00);
    writedata(0x9F);  // XEND = 159
    writecommand(ST7735_GMCTRP1);
    writedata(0x0f);
    writedata(0x1a);
    writedata(0x0f);
    writedata(0x18);
    writedata(0x2f);
    writedata(0x28);
    writedata(0x20);
    writedata(0x22);
    writedata(0x1f);
    writedata(0x1b);
    writedata(0x23);
    writedata(0x37);
    writedata(0x00);
    writedata(0x07);
    writedata(0x02);
    writedata(0x10);
    writecommand(ST7735_GMCTRN1);
    writedata(0x0f);
    writedata(0x1b);
    writedata(0x0f);
    writedata(0x17);
    writedata(0x33);
    writedata(0x2c);
    writedata(0x29);
    writedata(0x2e);
    writedata(0x30);
    writedata(0x30);
    writedata(0x39);
    writedata(0x3f);
    writedata(0x00);
    writedata(0x07);
    writedata(0x03);
    writedata(0x10);
    writecommand(ST7735_DISPON);
    chip_delay_us(100);
    writecommand(ST7735_NORON);  // normal display on
    chip_delay_us(10);
    LCD_CS(HIGH);
}

@unmanaged
class Coord {
    x: i32;
    y: i32;
}

function max(a: i32, b: i32): i32 { return a > b ? a : b; }

function min(a: i32, b: i32): i32 { return a < b ? a : b; }
function diff(a: i32, b: i32): i32 { return a < b ? b-a : a-b; }

const DARK = 0X3DF7
const RED = 0x7C00
const GREEN = 0x03F0
const BLUE = 0x000F
const WHITE = 0xFFFF
const BLACK = 0x0000


const PURPLE = (RED | BLUE)
const CYAN = (BLUE | GREEN)
const YELLOW = (GREEN | RED)

// Ball Abstraction.
const BSIZE = 10
const PSIZE = 40
const PYPOS = 150
const BCOL = RED
const BCOL2 = CYAN
//const BGCOL = (GREEN & DARK)
const BGCOL = BLACK
const PCOL = YELLOW

@unmanaged
class Ball {
    pos: Coord;
    speed: Coord;
}

export function main(): void {
    // ball state
    let ball: Ball = {
        pos: {
            x: 1000,
            y: 1000,
        },
        speed: {
            x: 50,
            y: 50
        }
    };
    // end init ball
    // end ball state
    // pin mode
    chip_pin_mode(CS, OUTPUT);
    chip_pin_mode(DC, OUTPUT);
    chip_pin_mode(SDA, OUTPUT);
    chip_pin_mode(SCK, OUTPUT);
    chip_pin_mode(RESET, OUTPUT);
    chip_pin_mode(BUTTON, INPUT);

    ST7735_initR();
    chip_fill_screen(BGCOL);
    let x = 64 - 20; // padle pos


    while (1) {
        // update ball
        let nextX = ball.pos.x + ball.speed.x;
        if(ball.speed.x > 0){
            chip_fill_rect(
               ball.pos.x/ 100, ball.pos.y / 100,
                diff(ball.pos.x/100,nextX/100), BSIZE,
                BGCOL);
        } else {
            chip_fill_rect(
                nextX/100 + BSIZE, ball.pos.y / 100,
                diff(ball.pos.x/100,nextX/100), BSIZE,
                BGCOL);
        }
        let nextY = ball.pos.y + ball.speed.y;
        if(ball.speed.y > 0){
            chip_fill_rect(
                ball.pos.x/ 100, ball.pos.y / 100,
                BSIZE,diff(ball.pos.y/100,nextY/100),
                BGCOL);
        } else {
            chip_fill_rect(
                ball.pos.x / 100, nextY/100 + BSIZE, //
                BSIZE,diff(ball.pos.y/100,nextY/100),
                BGCOL); //

        }
        //chip_fill_rect(ball.pos.x / 100, ball.pos.y / 100, BSIZE, BSIZE, BGCOL);
        ball.pos.x += ball.speed.x;
        ball.pos.y += ball.speed.y;
        chip_fill_rect(ball.pos.x / 100, ball.pos.y / 100, BSIZE, BSIZE,
                       ball.pos.y/100 + BSIZE >= PYPOS ? BCOL2 : BCOL);
        // bounce of the wall
        if (ball.pos.x + BSIZE * 100 > 12800) {
            ball.speed.x *= -1;
        }
        if (ball.pos.y + BSIZE * 100 > 16400) {
            ball.speed.y *= -1;
        }
        if (ball.pos.y < 0) {
            ball.speed.y *= -1;
        }
        if (ball.pos.x < 0) {
            ball.speed.x *= -1;
        }
        // end update ball
        // update paddle

        if (ball.pos.y/100 + BSIZE >= PYPOS) {
            if (ball.pos.x/100 + BSIZE > x && ball.pos.x/100 < x + PSIZE && ball.speed.y > 0) {
                ball.speed.y *= -1;
                ball.speed.y += ball.speed.y < 0 ? -1 : 1;
                ball.speed.x += ball.speed.x < 0 ? -1 : 1;

            }
        }

        chip_fill_rect(x, PYPOS, PSIZE, 5, BGCOL);
        x = chip_analog_read(BUTTON);
        chip_fill_rect(x, PYPOS, PSIZE, 5, PCOL);

        chip_delay_us(50);
    }
}

