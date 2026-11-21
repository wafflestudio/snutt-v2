CREATE TABLE map_walk_node
(
    id               BIGINT AUTO_INCREMENT PRIMARY KEY,
    latitude         DOUBLE      NOT NULL,
    longitude        DOUBLE      NOT NULL,
    elevation_m      DOUBLE      NOT NULL,
    elevation_source VARCHAR(16) NOT NULL,
    source           VARCHAR(16) NOT NULL,
    created_at       DATETIME(6) NOT NULL,
    updated_at       DATETIME(6) NOT NULL
);

CREATE TABLE map_structure
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(64) NOT NULL DEFAULT '',
    polygon    JSON        NOT NULL,
    floors     INT         NULL,
    source     VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL
);

CREATE TABLE map_walk_edge
(
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    node_a_id           BIGINT       NOT NULL,
    node_b_id           BIGINT       NOT NULL,
    kind                VARCHAR(16)  NOT NULL,
    structure_id        BIGINT       NULL,
    is_covered          BOOLEAN      NOT NULL DEFAULT FALSE,
    is_bridge_or_tunnel BOOLEAN      NOT NULL DEFAULT FALSE,
    is_lit              BOOLEAN      NULL,
    opening_hours       VARCHAR(255) NULL,
    is_blocked          BOOLEAN      NOT NULL DEFAULT FALSE,
    is_estimated        BOOLEAN      NOT NULL DEFAULT FALSE,
    source              VARCHAR(16)  NOT NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    CONSTRAINT fk_map_walk_edge_node_a FOREIGN KEY (node_a_id) REFERENCES map_walk_node (id),
    CONSTRAINT fk_map_walk_edge_node_b FOREIGN KEY (node_b_id) REFERENCES map_walk_node (id),
    CONSTRAINT fk_map_walk_edge_structure FOREIGN KEY (structure_id) REFERENCES map_structure (id),
    CONSTRAINT uk_map_walk_edge UNIQUE (node_a_id, node_b_id, kind),
    CONSTRAINT ck_map_walk_edge_node_order CHECK (node_a_id < node_b_id),
    CONSTRAINT ck_map_walk_edge_indoor_structure CHECK ((kind = 'INDOOR') = (structure_id IS NOT NULL))
);

CREATE TABLE map_building
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    building_number VARCHAR(16)  NOT NULL,
    campus          VARCHAR(16)  NOT NULL,
    name_kor        VARCHAR(64)  NOT NULL,
    name_eng        VARCHAR(128) NOT NULL DEFAULT '',
    latitude        DOUBLE       NOT NULL,
    longitude       DOUBLE       NOT NULL,
    structure_id    BIGINT       NULL,
    source          VARCHAR(16)  NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    CONSTRAINT uk_map_building UNIQUE (building_number, campus),
    CONSTRAINT fk_map_building_structure FOREIGN KEY (structure_id) REFERENCES map_structure (id)
);

CREATE TABLE map_entrance
(
    id                       BIGINT AUTO_INCREMENT PRIMARY KEY,
    structure_id             BIGINT       NOT NULL,
    node_id                  BIGINT       NOT NULL,
    floor                    VARCHAR(8)   NULL,
    is_wheelchair_accessible BOOLEAN      NULL,
    opening_hours            VARCHAR(255) NULL,
    source                   VARCHAR(16)  NOT NULL,
    created_at               DATETIME(6)  NOT NULL,
    updated_at               DATETIME(6)  NOT NULL,
    CONSTRAINT uk_map_entrance UNIQUE (structure_id, node_id),
    CONSTRAINT fk_map_entrance_structure FOREIGN KEY (structure_id) REFERENCES map_structure (id),
    CONSTRAINT fk_map_entrance_node FOREIGN KEY (node_id) REFERENCES map_walk_node (id)
);

CREATE TABLE map_access_point
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    building_id BIGINT      NOT NULL,
    node_id     BIGINT      NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    CONSTRAINT uk_map_access_point UNIQUE (building_id, node_id),
    CONSTRAINT fk_map_access_point_building FOREIGN KEY (building_id) REFERENCES map_building (id),
    CONSTRAINT fk_map_access_point_node FOREIGN KEY (node_id) REFERENCES map_walk_node (id)
);

CREATE TABLE map_transit_stop
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(64) NOT NULL,
    ars_id     VARCHAR(16) NULL,
    latitude   DOUBLE      NOT NULL,
    longitude  DOUBLE      NOT NULL,
    node_id    BIGINT      NOT NULL,
    source     VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_map_transit_stop_ars_id UNIQUE (ars_id),
    CONSTRAINT fk_map_transit_stop_node FOREIGN KEY (node_id) REFERENCES map_walk_node (id)
);

CREATE TABLE map_transit_line
(
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    name                 VARCHAR(32) NOT NULL,
    kind                 VARCHAR(16) NOT NULL,
    headway_min          INT         NOT NULL,
    headway_note         VARCHAR(64) NOT NULL DEFAULT '',
    is_headway_estimated BOOLEAN     NOT NULL,
    source               VARCHAR(16) NOT NULL,
    created_at           DATETIME(6) NOT NULL,
    updated_at           DATETIME(6) NOT NULL,
    CONSTRAINT uk_map_transit_line_name UNIQUE (name)
);

CREATE TABLE map_transit_line_stop
(
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    line_id               BIGINT      NOT NULL,
    run_no                INT         NOT NULL,
    seq                   INT         NOT NULL,
    stop_id               BIGINT      NOT NULL,
    display_name          VARCHAR(64) NULL,
    ride_sec_to_next      INT         NULL,
    is_ride_sec_estimated BOOLEAN     NOT NULL DEFAULT TRUE,
    road_m_to_next        INT         NULL,
    path_to_next          JSON        NULL,
    created_at            DATETIME(6) NOT NULL,
    updated_at            DATETIME(6) NOT NULL,
    CONSTRAINT uk_map_transit_line_stop UNIQUE (line_id, run_no, seq),
    CONSTRAINT fk_map_transit_line_stop_line FOREIGN KEY (line_id) REFERENCES map_transit_line (id),
    CONSTRAINT fk_map_transit_line_stop_stop FOREIGN KEY (stop_id) REFERENCES map_transit_stop (id)
);

CREATE TABLE map_state
(
    id         BIGINT      NOT NULL PRIMARY KEY,
    version    BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT ck_map_state_single_row CHECK (id = 1)
);

INSERT INTO map_state (id, version, created_at, updated_at)
VALUES (1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));
