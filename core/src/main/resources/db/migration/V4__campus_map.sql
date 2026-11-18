CREATE TABLE campus_walk_node
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

CREATE TABLE campus_structure
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    name       VARCHAR(64) NOT NULL DEFAULT '',
    polygon    JSON        NOT NULL,
    floors     INT         NULL,
    source     VARCHAR(16) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL
);

CREATE TABLE campus_walk_edge
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
    CONSTRAINT fk_campus_walk_edge_node_a FOREIGN KEY (node_a_id) REFERENCES campus_walk_node (id),
    CONSTRAINT fk_campus_walk_edge_node_b FOREIGN KEY (node_b_id) REFERENCES campus_walk_node (id),
    CONSTRAINT fk_campus_walk_edge_structure FOREIGN KEY (structure_id) REFERENCES campus_structure (id),
    CONSTRAINT ck_campus_walk_edge_distinct_nodes CHECK (node_a_id <> node_b_id),
    CONSTRAINT ck_campus_walk_edge_indoor_structure CHECK ((kind = 'INDOOR') = (structure_id IS NOT NULL))
);

CREATE TABLE campus_building
(
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    building_number VARCHAR(16)  NOT NULL,
    name_kor        VARCHAR(64)  NOT NULL,
    name_eng        VARCHAR(128) NOT NULL DEFAULT '',
    latitude        DOUBLE       NOT NULL,
    longitude       DOUBLE       NOT NULL,
    structure_id    BIGINT       NULL,
    source          VARCHAR(16)  NOT NULL,
    created_at      DATETIME(6)  NOT NULL,
    updated_at      DATETIME(6)  NOT NULL,
    CONSTRAINT uk_campus_building_number UNIQUE (building_number),
    CONSTRAINT fk_campus_building_structure FOREIGN KEY (structure_id) REFERENCES campus_structure (id)
);

CREATE TABLE campus_entrance
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
    CONSTRAINT uk_campus_entrance UNIQUE (structure_id, node_id),
    CONSTRAINT fk_campus_entrance_structure FOREIGN KEY (structure_id) REFERENCES campus_structure (id),
    CONSTRAINT fk_campus_entrance_node FOREIGN KEY (node_id) REFERENCES campus_walk_node (id)
);

CREATE TABLE campus_access_point
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    building_id BIGINT      NOT NULL,
    node_id     BIGINT      NOT NULL,
    created_at  DATETIME(6) NOT NULL,
    updated_at  DATETIME(6) NOT NULL,
    CONSTRAINT uk_campus_access_point UNIQUE (building_id, node_id),
    CONSTRAINT fk_campus_access_point_building FOREIGN KEY (building_id) REFERENCES campus_building (id),
    CONSTRAINT fk_campus_access_point_node FOREIGN KEY (node_id) REFERENCES campus_walk_node (id)
);

CREATE TABLE campus_transit_stop
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
    CONSTRAINT uk_campus_transit_stop_ars_id UNIQUE (ars_id),
    CONSTRAINT fk_campus_transit_stop_node FOREIGN KEY (node_id) REFERENCES campus_walk_node (id)
);

CREATE TABLE campus_transit_line
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
    CONSTRAINT uk_campus_transit_line_name UNIQUE (name)
);

CREATE TABLE campus_transit_line_stop
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
    CONSTRAINT uk_campus_transit_line_stop UNIQUE (line_id, run_no, seq),
    CONSTRAINT fk_campus_transit_line_stop_line FOREIGN KEY (line_id) REFERENCES campus_transit_line (id),
    CONSTRAINT fk_campus_transit_line_stop_stop FOREIGN KEY (stop_id) REFERENCES campus_transit_stop (id)
);

CREATE TABLE campus_map_state
(
    id         BIGINT      NOT NULL PRIMARY KEY,
    version    BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT ck_campus_map_state_single_row CHECK (id = 1)
);

INSERT INTO campus_map_state (id, version, created_at, updated_at)
VALUES (1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6));

CREATE TABLE campus_map_change
(
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    version     BIGINT       NOT NULL,
    user_id     BIGINT       NOT NULL,
    description VARCHAR(255) NOT NULL DEFAULT '',
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    CONSTRAINT uk_campus_map_change_version UNIQUE (version),
    INDEX idx_campus_map_change_user (user_id, id)
);

CREATE TABLE campus_map_change_item
(
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    change_id    BIGINT      NOT NULL,
    target_type  VARCHAR(32) NOT NULL,
    target_id    BIGINT      NOT NULL,
    action       VARCHAR(16) NOT NULL,
    before_value JSON        NULL,
    after_value  JSON        NULL,
    created_at   DATETIME(6) NOT NULL,
    updated_at   DATETIME(6) NOT NULL,
    CONSTRAINT fk_campus_map_change_item_change FOREIGN KEY (change_id) REFERENCES campus_map_change (id),
    INDEX idx_campus_map_change_item_target (target_type, target_id, id)
);

CREATE TABLE campus_map_release
(
    id         BIGINT AUTO_INCREMENT PRIMARY KEY,
    version    BIGINT      NOT NULL,
    user_id    BIGINT      NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_campus_map_release_version UNIQUE (version)
);
