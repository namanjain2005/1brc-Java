from collections import defaultdict
import sys
import time
from temperature import format_average, format_value, parse_scaled

sys.stdout.reconfigure(encoding='utf-8')

# Load all data first
with open('weather_stations.csv', 'r', encoding='utf-8') as f:
    lines = f.readlines()

# Start timing after loading
start = time.perf_counter()

data = defaultdict(lambda: [None, None, 0, 0])

for line in lines:
    station, temp = line.rstrip('\n').split(';')
    t = parse_scaled(temp)
    d = data[station]
    if d[0] is None or t < d[0]: d[0] = t
    if d[1] is None or t > d[1]: d[1] = t
    d[2] += t
    d[3] += 1

for station in sorted(data):
    d = data[station]
    print(f"{station};{format_value(d[0])};{format_average(d[2], d[3])};{format_value(d[1])}")

end = time.perf_counter()
print(f"Elapsed time: {end - start:.6f} seconds")
