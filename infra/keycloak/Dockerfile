# syntax=docker/dockerfile:1
FROM quay.io/keycloak/keycloak:26.8.0 AS builder
ENV KC_DB=postgres
ENV KC_HEALTH_ENABLED=true
# Build-time options are baked in so the container can start with --optimized and skip the build on every start.
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8.0
COPY --from=builder /opt/keycloak/ /opt/keycloak/
COPY orderflow-realm.json /opt/keycloak/data/import/orderflow-realm.json
# AWS JDBC URLs verify the RDS certificate against this bundle (sslmode=verify-full); it goes into an existing
# directory because ADD would create a missing parent with the file mode (644), which the runtime user cannot enter.
ADD --checksum=sha256:fe45bbebf92ad3e27a583bbb2ddd1553c521ed4d49af5514dc0a40372ea5395c --chmod=644 https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem /opt/keycloak/conf/rds-global-bundle.pem
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized", "--import-realm"]
