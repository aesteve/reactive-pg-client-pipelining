docker run \
  -p 5432:5432 \
  --name postgresq-vertx-reproducer \
  -e POSTGRES_PASSWORD=mysecretpassword \
  -d postgres
