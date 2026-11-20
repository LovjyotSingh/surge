# Client addresses exempt from nginx's per-IP connection and request limits, one "<address-or-CIDR> 1;" per line.
# Empty on purpose. compose.loadtest.yaml mounts web/nginx-loadtest-exempt.conf here for the load-test container,
# whose 10,000 simulated buyers all share one address.
