Performance tests
-----------------

Examples of basic usage:

    ./perf_elias_fano 2> perf_ef.json
    python3 ../perf/plot.py perf_ef.json

    ./perf_elias_fano -tc=access_dense
    ./perf_cache_line_elias_fano
    python3 ../perf/plot.py ef_clef_comparison_dense_access.json
