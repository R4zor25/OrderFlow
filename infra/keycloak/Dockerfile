FROM quay.io/keycloak/keycloak:26.8.0 AS builder
ENV KC_DB=postgres
ENV KC_HEALTH_ENABLED=true
# Build-time options are baked in so the container can start with --optimized and skip the build on every start.
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak:26.8.0
COPY --from=builder /opt/keycloak/ /opt/keycloak/
COPY orderflow-realm.json /opt/keycloak/data/import/orderflow-realm.json
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start", "--optimized", "--import-realm"]
