UNITS_PER_DEGREE = 10_000


def parse_scaled(text):
    negative = text.startswith("-")
    if text[:1] in {"-", "+"}:
        text = text[1:]

    whole, dot, fraction = text.partition(".")
    if not whole or not whole.isdigit() or (dot and not fraction.isdigit()):
        raise ValueError(f"Invalid temperature: {text}")
    if len(fraction) > 4:
        raise ValueError(f"Temperature has more than four decimal places: {text}")
    fraction = fraction.ljust(4, "0")
    scaled = int(whole) * UNITS_PER_DEGREE + int(fraction or "0")
    return -scaled if negative else scaled


def format_value(scaled):
    return f"{scaled / UNITS_PER_DEGREE:.1f}"


def format_average(total, count):
    return f"{total / (count * UNITS_PER_DEGREE):.1f}"
