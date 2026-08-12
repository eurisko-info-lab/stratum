struct Pair<T> {
    a: T,
    b: T,
}

struct Bounded<T: Clone> {
    x: T,
}

enum Shape {
    Circle(i32),
    Square(i32),
}

trait Speak {
    fn speak(&self) -> i32;
}

impl Speak for Shape {
    fn speak(&self) -> i32 {
        1
    }
}

fn arithmetic(a: i32, b: i32) -> i32 {
    a + b * 2 - 1
}

fn shift(x: i32) -> i32 {
    x >> 1
}

fn nested_generic() -> i32 {
    let v: Vec<Vec<i32>> = f();
    0
}

fn area(s: Shape) -> i32 {
    match s {
        Circle(r) => r * r,
        Square(w) => w * w,
    }
}

fn branch(x: i32) -> i32 {
    if x > 0 {
        x
    } else {
        0 - x
    }
}

fn loopy() -> i32 {
    let mut i = 0;
    loop {
        i = i + 1;
        if i > 10 {
            break;
        }
    }
    i
}

fn arr() -> i32 {
    let a = [1, 2, 3];
    a[0]
}

fn pt() -> i32 {
    let p = Point { x: 1, y: 2 };
    p.x
}
