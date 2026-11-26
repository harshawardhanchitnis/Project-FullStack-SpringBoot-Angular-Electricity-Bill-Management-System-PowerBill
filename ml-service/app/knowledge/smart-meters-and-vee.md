# Smart meters, VEE and anomaly checks

Smart meters deployed under the Revamped Distribution Sector Scheme (RDSS) report hourly interval readings. Before
billing, readings pass validation, estimation and editing (VEE). Readings above 1.5 times the sanctioned load,
unexpected solar export, out-of-range voltage or a low power factor are flagged and excluded from bills until an
engineer corrects them. Missing hours can be estimated from the same hour on previous days.

The anomaly detector also looks for flat zero readings, sudden sustained drops that can indicate a bypass, and
nighttime baseload jumps that often point to faulty appliances. Field engineers confirm each case as a fault, tamper
or false positive.
