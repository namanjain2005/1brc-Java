import csv
from collections import defaultdict
import sys
import time
sys.stdout.reconfigure(encoding='utf-8')
import time

start = time.time()

data = defaultdict(lambda: [float('inf'), float('-inf'), 0.0, 0])

with open('weather_stations.csv', 'r', encoding='utf-8') as f:
    for line in f:
        station, temp = line.strip().split(';')
        t = float(temp)
        d = data[station]
        if t < d[0]: d[0] = t
        if t > d[1]: d[1] = t
        d[2] += t
        d[3] += 1
for station in sorted(data):
    d = data[station]
    print(f"{station};{d[0]:.1f};{d[2]/d[3]:.1f};{d[1]:.1f}")
end = time.time()
print("Time taken:", end - start, "seconds")
