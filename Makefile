.PHONY: help up down db-init db-reset api web test clean

help:
	@grep -E '^[a-z-]+:.*?## .*$$' $(MAKEFILE_LIST) | awk 'BEGIN{FS=":.*?## "}{printf "  %-12s %s\n", $$1, $$2}'

up:        ## Start the full stack (requires Docker)
	docker compose up --build

down:      ## Stop the stack
	docker compose down

db-init:   ## Create roles + databases on a locally installed PostgreSQL
	psql -U postgres -d postgres -v ON_ERROR_STOP=1 -f docker/postgres/init/00-roles.sql
	psql -U postgres -d postgres -c "CREATE DATABASE aea OWNER aea_owner;"      || true
	psql -U postgres -d postgres -c "CREATE DATABASE aea_test OWNER aea_owner;" || true

db-reset:  ## Drop and recreate the local databases
	psql -U postgres -d postgres -c "DROP DATABASE IF EXISTS aea;"
	psql -U postgres -d postgres -c "DROP DATABASE IF EXISTS aea_test;"
	$(MAKE) db-init

api:       ## Run the API against the local database
	cd apps/api && mvn spring-boot:run

web:       ## Run the web dev server
	cd apps/web && npm run dev

test:      ## Run the API test suite (migrations, ArchUnit, RLS)
	cd apps/api && mvn -B verify

clean:
	cd apps/api && mvn -q clean
	rm -rf apps/web/dist apps/web/node_modules
