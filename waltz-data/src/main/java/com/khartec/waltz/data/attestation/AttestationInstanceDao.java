/*
 * Waltz - Enterprise Architecture
 * Copyright (C) 2016, 2017, 2018, 2019 Waltz open source project
 * See README.md for more information
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific
 *
 */

package com.khartec.waltz.data.attestation;

import com.khartec.waltz.data.InlineSelectFieldFactory;
import com.khartec.waltz.model.EntityKind;
import com.khartec.waltz.model.EntityLifecycleStatus;
import com.khartec.waltz.model.EntityReference;
import com.khartec.waltz.model.attestation.*;
import com.khartec.waltz.schema.tables.records.AttestationInstanceRecord;
import org.jooq.*;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.khartec.waltz.common.Checks.checkNotNull;
import static com.khartec.waltz.common.ListUtilities.newArrayList;
import static com.khartec.waltz.model.EntityReference.mkRef;
import static com.khartec.waltz.schema.Tables.*;
import static com.khartec.waltz.schema.tables.Application.APPLICATION;


@Repository
public class AttestationInstanceDao {

    private static final Field<String> ENTITY_NAME_FIELD = InlineSelectFieldFactory.mkNameField(
            ATTESTATION_INSTANCE.PARENT_ENTITY_ID,
            ATTESTATION_INSTANCE.PARENT_ENTITY_KIND,
            newArrayList(EntityKind.values()))
            .as("entity_name");

    private final DSLContext dsl;

    private static final RecordMapper<Record, AttestationInstance> TO_DOMAIN_MAPPER = r -> {
        AttestationInstanceRecord record = r.into(ATTESTATION_INSTANCE);
        return ImmutableAttestationInstance.builder()
                .id(record.getId())
                .attestationRunId(record.getAttestationRunId())
                .parentEntity(mkRef(
                        EntityKind.valueOf(record.getParentEntityKind()),
                        record.getParentEntityId(),
                        r.getValue(ENTITY_NAME_FIELD)))
                .attestedAt(Optional.ofNullable(record.getAttestedAt()).map(Timestamp::toLocalDateTime))
                .attestedBy(Optional.ofNullable(record.getAttestedBy()))
                .attestedEntityKind(EntityKind.valueOf(record.getAttestedEntityKind()))
                .attestedEntityId(r.get(ATTESTATION_RUN.ATTESTED_ENTITY_ID))
                .build();
    };


    @Autowired
    public AttestationInstanceDao(DSLContext dsl) {
        checkNotNull(dsl, "dsl cannot be null");
        this.dsl = dsl;
    }


    public AttestationInstance getById (long id) {

        return dsl
                .select(ATTESTATION_INSTANCE.fields())
                .select(ENTITY_NAME_FIELD)
                .select(ATTESTATION_RUN.ATTESTED_ENTITY_ID)
                .from(ATTESTATION_INSTANCE)
                .innerJoin(ATTESTATION_RUN)
                .on(ATTESTATION_RUN.ID.eq(ATTESTATION_INSTANCE.ATTESTATION_RUN_ID))
                .where(ATTESTATION_INSTANCE.ID.eq(id))
                .fetchOne(TO_DOMAIN_MAPPER);
    }


    public long create(AttestationInstance attestationInstance) {
        checkNotNull(attestationInstance, "attestationInstance cannot be null");

        AttestationInstanceRecord record = dsl.newRecord(ATTESTATION_INSTANCE);
        record.setAttestationRunId(attestationInstance.attestationRunId());
        record.setParentEntityKind(attestationInstance.parentEntity().kind().name());
        record.setParentEntityId(attestationInstance.parentEntity().id());
        record.setAttestedEntityKind(attestationInstance.attestedEntityKind().name());

        record.store();

        return record.getId();
    }


    public List<AttestationInstance> findByRecipient(String userId, boolean unattestedOnly) {
        Condition condition = ATTESTATION_INSTANCE_RECIPIENT.USER_ID.eq(userId);
        if(unattestedOnly) {
            condition = condition.and(ATTESTATION_INSTANCE.ATTESTED_AT.isNull());
        }

        return dsl
                .select(ATTESTATION_INSTANCE.fields())
                .select(ENTITY_NAME_FIELD)
                .select(ATTESTATION_RUN.ATTESTED_ENTITY_ID)
                .from(ATTESTATION_INSTANCE)
                .innerJoin(ATTESTATION_RUN)
                .on(ATTESTATION_RUN.ID.eq(ATTESTATION_INSTANCE.ATTESTATION_RUN_ID))
                .innerJoin(ATTESTATION_INSTANCE_RECIPIENT)
                .on(ATTESTATION_INSTANCE_RECIPIENT.ATTESTATION_INSTANCE_ID.eq(ATTESTATION_INSTANCE.ID))
                .where(condition)
                .fetch(TO_DOMAIN_MAPPER);
    }


    /**
     * find historically completed attestations for all parent refs which are currently pending attestation
     * by the provided user
     * @param userId  id of user
     * @return List of attestation instances
     */
    public List<AttestationInstance> findHistoricalForPendingByUserId(String userId) {
        // fetch the parent refs for attestations currently pending for the user
        Select<Record2<String, Long>> currentlyPendingAttestationParentRefs = dsl
                .selectDistinct(ATTESTATION_INSTANCE.PARENT_ENTITY_KIND, ATTESTATION_INSTANCE.PARENT_ENTITY_ID)
                .from(ATTESTATION_INSTANCE)
                .innerJoin(ATTESTATION_INSTANCE_RECIPIENT)
                .on(ATTESTATION_INSTANCE_RECIPIENT.ATTESTATION_INSTANCE_ID.eq(ATTESTATION_INSTANCE.ID))
                .where(ATTESTATION_INSTANCE_RECIPIENT.USER_ID.eq(userId))
                .and(ATTESTATION_INSTANCE.ATTESTED_AT.isNull());

        // get the historically completed attestations based on the above list of parent refs
        return dsl
                .select(ATTESTATION_INSTANCE.fields())
                .select(ENTITY_NAME_FIELD)
                .select(ATTESTATION_RUN.ATTESTED_ENTITY_ID)
                .from(ATTESTATION_INSTANCE)
                .innerJoin(ATTESTATION_RUN)
                .on(ATTESTATION_RUN.ID.eq(ATTESTATION_INSTANCE.ATTESTATION_RUN_ID))
                .innerJoin(currentlyPendingAttestationParentRefs)
                .on(currentlyPendingAttestationParentRefs.field(ATTESTATION_INSTANCE.PARENT_ENTITY_KIND).eq(ATTESTATION_INSTANCE.PARENT_ENTITY_KIND)
                        .and(currentlyPendingAttestationParentRefs.field(ATTESTATION_INSTANCE.PARENT_ENTITY_ID).eq(ATTESTATION_INSTANCE.PARENT_ENTITY_ID))
                )
                .where(ATTESTATION_INSTANCE.ATTESTED_AT.isNotNull())
                .orderBy(ATTESTATION_INSTANCE.ATTESTED_AT.desc())
                .fetch(TO_DOMAIN_MAPPER);
    }


    public List<AttestationInstance> findByEntityReference(EntityReference ref) {
        return dsl
                .select(ATTESTATION_INSTANCE.fields())
                .select(ENTITY_NAME_FIELD)
                .select(ATTESTATION_RUN.ATTESTED_ENTITY_ID)
                .from(ATTESTATION_INSTANCE)
                .innerJoin(ATTESTATION_RUN)
                .on(ATTESTATION_RUN.ID.eq(ATTESTATION_INSTANCE.ATTESTATION_RUN_ID))
                .where(ATTESTATION_INSTANCE.PARENT_ENTITY_KIND.eq(ref.kind().name()))
                .and(ATTESTATION_INSTANCE.PARENT_ENTITY_ID.eq(ref.id()))
                .fetch(TO_DOMAIN_MAPPER);
    }


    public boolean attestInstance(long instanceId, String attestedBy, LocalDateTime dateTime) {
        return dsl
                .update(ATTESTATION_INSTANCE)
                .set(ATTESTATION_INSTANCE.ATTESTED_BY, attestedBy)
                .set(ATTESTATION_INSTANCE.ATTESTED_AT, Timestamp.valueOf(dateTime))
                .where(ATTESTATION_INSTANCE.ID.eq(instanceId).and(ATTESTATION_INSTANCE.ATTESTED_AT.isNull()))
                .execute() == 1;
    }


    public List<AttestationInstance> findByRunId(long runId) {
        return dsl
                .select(ATTESTATION_INSTANCE.fields())
                .select(ENTITY_NAME_FIELD)
                .select(ATTESTATION_RUN.ATTESTED_ENTITY_ID)
                .from(ATTESTATION_INSTANCE)
                .innerJoin(ATTESTATION_RUN)
                .on(ATTESTATION_RUN.ID.eq(ATTESTATION_INSTANCE.ATTESTATION_RUN_ID))
                .where(ATTESTATION_INSTANCE.ATTESTATION_RUN_ID.eq(runId))
                .fetch(TO_DOMAIN_MAPPER);
    }


    public int cleanupOrphans() {

        Select<Record1<Long>> orphanAttestationIds = DSL
                .selectDistinct(ATTESTATION_INSTANCE.ID)
                .from(ATTESTATION_INSTANCE)
                .leftJoin(APPLICATION)
                .on(APPLICATION.ID.eq(ATTESTATION_INSTANCE.PARENT_ENTITY_ID)
                        .and(ATTESTATION_INSTANCE.PARENT_ENTITY_KIND.eq(EntityKind.APPLICATION.name())))
                .where(ATTESTATION_INSTANCE.ATTESTED_AT.isNull())
                .and(APPLICATION.ID.isNull()
                        .or(APPLICATION.ENTITY_LIFECYCLE_STATUS.eq(EntityLifecycleStatus.REMOVED.name()))
                        .or(APPLICATION.IS_REMOVED.eq(true)));

        dsl.deleteFrom(ATTESTATION_INSTANCE_RECIPIENT)
                .where(ATTESTATION_INSTANCE_RECIPIENT.ATTESTATION_INSTANCE_ID.in(orphanAttestationIds))
                .execute();

        int numberOfInstancesDeleted = dsl.deleteFrom(ATTESTATION_INSTANCE)
                .where(ATTESTATION_INSTANCE.ID.in(orphanAttestationIds))
                .execute();

        return numberOfInstancesDeleted;
    }


    public List<AttestationInstance> findForEntityByRecipient(AttestEntityCommand command,
                                                              String userId,
                                                              boolean unattestedOnly) {
        Condition maybeUnattestedOnlyCondition = unattestedOnly
                ? ATTESTATION_INSTANCE.ATTESTED_AT.isNull()
                : DSL.trueCondition();

        return dsl
                .select(ATTESTATION_INSTANCE.fields())
                .select(ENTITY_NAME_FIELD)
                .select(ATTESTATION_RUN.ATTESTED_ENTITY_ID)
                .from(ATTESTATION_INSTANCE)
                .innerJoin(ATTESTATION_RUN)
                .on(ATTESTATION_INSTANCE.ATTESTATION_RUN_ID.eq(ATTESTATION_RUN.ID))
                .innerJoin(ATTESTATION_INSTANCE_RECIPIENT)
                .on(ATTESTATION_INSTANCE_RECIPIENT.ATTESTATION_INSTANCE_ID.eq(ATTESTATION_INSTANCE.ID))
                .where(ATTESTATION_INSTANCE_RECIPIENT.USER_ID.eq(userId))
                .and(ATTESTATION_RUN.ATTESTED_ENTITY_KIND.eq(command.attestedEntityKind().name())
                .and(ATTESTATION_RUN.ATTESTED_ENTITY_ID.eq(command.attestedEntityId()))
                .and(ATTESTATION_INSTANCE.PARENT_ENTITY_ID.eq(command.entityReference().id()))
                .and(ATTESTATION_INSTANCE.PARENT_ENTITY_KIND.eq(command.entityReference().kind().name())))
                .and(maybeUnattestedOnlyCondition)
                .fetch(TO_DOMAIN_MAPPER);
    }

    public List<AttestationInstance> findByIdSelector(Select<Record1<Long>> selector) {
        return dsl
                .select(ATTESTATION_INSTANCE.fields())
                .select(ENTITY_NAME_FIELD)
                .select(ATTESTATION_RUN.ATTESTED_ENTITY_ID)
                .from(ATTESTATION_INSTANCE)
                .innerJoin(ATTESTATION_RUN)
                .on(ATTESTATION_RUN.ID.eq(ATTESTATION_INSTANCE.ATTESTATION_RUN_ID))
                .where(ATTESTATION_INSTANCE.ID.in(selector))
                .and(ATTESTATION_INSTANCE.ATTESTED_AT.isNotNull())
                .fetch(TO_DOMAIN_MAPPER);
    }


    public Set<LatestMeasurableAttestationInfo> findLatestMeasurableAttestations(EntityReference ref){

        Field<Long> latest_attestation = DSL
                .firstValue(ATTESTATION_INSTANCE.ID)
                .over()
                .partitionBy(ATTESTATION_RUN.ATTESTED_ENTITY_ID)
                .orderBy(ATTESTATION_INSTANCE.ATTESTED_AT.desc().nullsLast())
                .as("latest_attestation");

        Table<Record> attestationsWithCategory = dsl
                .select(latest_attestation,
                        MEASURABLE_CATEGORY.ID.as("category_id"),
                        MEASURABLE_CATEGORY.NAME.as("category_name"),
                        MEASURABLE_CATEGORY.DESCRIPTION)
                .select(ATTESTATION_INSTANCE.ID.as("instance_id"),
                        ATTESTATION_INSTANCE.ATTESTED_AT,
                        ATTESTATION_INSTANCE.ATTESTED_BY)
                .select(ATTESTATION_RUN.ID.as("run_id"),
                        ATTESTATION_RUN.NAME.as("run_name"),
                        ATTESTATION_RUN.ISSUED_ON,
                        ATTESTATION_RUN.DUE_DATE)
                .select(ENTITY_NAME_FIELD)
                .from(ATTESTATION_INSTANCE)
                .innerJoin(ATTESTATION_RUN)
                .on(ATTESTATION_INSTANCE.ATTESTATION_RUN_ID.eq(ATTESTATION_RUN.ID))
                .innerJoin(MEASURABLE_CATEGORY)
                .on(ATTESTATION_RUN.ATTESTED_ENTITY_KIND.eq(EntityKind.MEASURABLE_CATEGORY.name())
                        .and(ATTESTATION_RUN.ATTESTED_ENTITY_ID.eq(MEASURABLE_CATEGORY.ID)))
                .where(ATTESTATION_INSTANCE.PARENT_ENTITY_ID.eq(ref.id())
                        .and(ATTESTATION_INSTANCE.PARENT_ENTITY_KIND.eq(ref.kind().name())))
                .asTable();

        Result<Record> latestAttestationsWithCategory = dsl
                .selectFrom(attestationsWithCategory)
                .where(attestationsWithCategory.field(latest_attestation)
                        .eq(attestationsWithCategory.field("instance_id", Long.class)))
                .fetch();

        return latestAttestationsWithCategory
                .stream()
                .map(r -> {

                    EntityReference categoryRef = mkRef(
                            EntityKind.MEASURABLE_CATEGORY,
                            r.get("category_id", Long.class),
                            r.get("category_name", String.class),
                            r.get(MEASURABLE_CATEGORY.DESCRIPTION));

                    EntityReference instanceRef = mkRef(EntityKind.ATTESTATION, r.get("instance_id", Long.class));

                    EntityReference runRef = mkRef(
                            EntityKind.ATTESTATION_RUN,
                            r.get("run_id", Long.class),
                            r.get("run_name", String.class));

                    ImmutableLatestMeasurableAttestationInfo latestMeasurableAttestationInfo = ImmutableLatestMeasurableAttestationInfo.builder()
                            .categoryRef(categoryRef)
                            .attestationInstanceRef(instanceRef)
                            .attestationRunRef(runRef)
                            .issuedOn(r.get(ATTESTATION_RUN.ISSUED_ON))
                            .dueDate(r.get(ATTESTATION_RUN.DUE_DATE))
                            .attestedAt(r.get(ATTESTATION_INSTANCE.ATTESTED_AT))
                            .attestedBy(r.get(ATTESTATION_INSTANCE.ATTESTED_BY))
                            .build();

                    return latestMeasurableAttestationInfo;
                })
                .collect(Collectors.toSet());
    }
}