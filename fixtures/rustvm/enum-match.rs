// expect: 25
enum Shape {
    Circle(i32),
    Square(i32),
}

fn area(s: Shape) -> i32 {
    match s {
        Circle(r) => r * r,
        Square(w) => w * w,
    }
}

fn main() -> i32 {
    area(Circle(5))
}
