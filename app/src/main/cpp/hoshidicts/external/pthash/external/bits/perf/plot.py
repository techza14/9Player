import matplotlib.pyplot as plt
import numpy as np
import json
import sys
import os
from math import log2, ceil, sqrt

# Check for command-line argument
if len(sys.argv) < 2:
    print("Usage: python plot_query.py <file.json>")
    sys.exit(1)

# Read the JSON file path from command-line
json_file = sys.argv[1]

# Load JSON data from each line
datasets = []
with open(json_file, 'r') as f:
    for line in f:
        if line.strip():
            datasets.append(json.loads(line))

num_plots = len(datasets)
cols = 2
rows = ceil(num_plots / cols)

# Set up subplots
fig, axs = plt.subplots(rows, cols, figsize=(cols * 6, rows * 4))
axs = axs.flatten()

# Determine global y-axis limits for each row
y_limits_per_row = []
for r in range(rows):
    y_vals = []
    for c in range(cols):
        i = r * cols + c
        if i < num_plots:
            y_vals.extend(datasets[i]["avg_ns_per_query"])
    if y_vals:
        y_min = min(y_vals)
        y_max = max(y_vals)
        y_limits_per_row.append((y_min-1, y_max+1))

# Generate each subplot
for i, data in enumerate(datasets):
    ax = axs[i]
    x = data["sequence_lengths"]
    y = data["avg_ns_per_query"]

    ax.plot(x, y, marker='o', markersize=4, linestyle='-', color='blue')
    ax.set_xscale('log', base=2)
    ax.set_xlabel("sequence length")
    ax.set_ylabel("avg. ns/query")
    ax.set_title(data.get("query", f"Query {i+1}"))
    ax.grid(True, which="both", linestyle='--', linewidth=0.5)

    # Set x-ticks explicitly at powers of 2
    log2_min = np.floor(np.log2(min(x)))
    log2_max = np.ceil(np.log2(max(x)))
    log2_ticks = np.arange(log2_min, log2_max + 1)
    x_ticks = [2**t for t in log2_ticks]
    x_labels = [f"$2^{{{int(t)}}}$" for t in log2_ticks]
    ax.set_xticks(x_ticks)
    ax.set_xticklabels(x_labels)

    # Set shared y-axis limits for this row
    row = i // cols
    ax.set_ylim(*y_limits_per_row[row])

# Hide any unused subplots
for j in range(i + 1, len(axs)):
    fig.delaxes(axs[j])

plt.tight_layout()

# Save as PDF
pdf_file = os.path.splitext(json_file)[0] + ".pdf"
plt.savefig(pdf_file)
print(f"Plot saved to {pdf_file}")
