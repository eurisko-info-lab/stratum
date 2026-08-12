// expect: 5
fn main() -> i32 {
    let x = 0 - 5;
    if x > 0 {
        x
    } else {
        0 - x
    }
}
