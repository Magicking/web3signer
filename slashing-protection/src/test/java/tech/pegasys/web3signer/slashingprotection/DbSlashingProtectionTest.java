/*
 * Copyright 2020 ConsenSys AG.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package tech.pegasys.web3signer.slashingprotection;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.refEq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import tech.pegasys.web3signer.slashingprotection.dao.SignedAttestation;
import tech.pegasys.web3signer.slashingprotection.dao.SignedAttestationsDao;
import tech.pegasys.web3signer.slashingprotection.dao.SignedBlock;
import tech.pegasys.web3signer.slashingprotection.dao.SignedBlocksDao;
import tech.pegasys.web3signer.slashingprotection.dao.Validator;
import tech.pegasys.web3signer.slashingprotection.dao.ValidatorsDao;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.tuweni.bytes.Bytes;
import org.apache.tuweni.units.bigints.UInt64;
import org.jdbi.v3.testing.JdbiRule;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
@SuppressWarnings("ConstantConditions")
public class DbSlashingProtectionTest {

  private static final int VALIDATOR_ID = 1;
  private static final UInt64 SLOT = UInt64.valueOf(2);
  private static final Bytes PUBLIC_KEY1 = Bytes.of(42);
  private static final Bytes PUBLIC_KEY2 = Bytes.of(43);
  private static final Bytes PUBLIC_KEY3 = Bytes.of(44);
  private static final Bytes SIGNING_ROOT = Bytes.of(3);
  private static final UInt64 SOURCE_EPOCH = UInt64.valueOf(10);
  private static final UInt64 TARGET_EPOCH = UInt64.valueOf(20);

  @Mock private ValidatorsDao validatorsDao;
  @Mock private SignedBlocksDao signedBlocksDao;
  @Mock private SignedAttestationsDao signedAttestationsDao;
  @Rule public JdbiRule db = JdbiRule.embeddedPostgres();

  private DbSlashingProtection dbSlashingProtection;

  @Before
  public void setup() {
    dbSlashingProtection =
        new DbSlashingProtection(
            db.getJdbi(),
            validatorsDao,
            signedBlocksDao,
            signedAttestationsDao,
            Map.of(PUBLIC_KEY1, VALIDATOR_ID));
  }

  @Test
  public void blockCanSignWhenNoMatchForPublicKey() {
    when(signedBlocksDao.findExistingBlock(any(), anyInt(), any())).thenReturn(Optional.empty());

    assertThat(dbSlashingProtection.maySignBlock(PUBLIC_KEY1, SIGNING_ROOT, SLOT)).isTrue();
    verify(signedBlocksDao).findExistingBlock(any(), eq(VALIDATOR_ID), eq(SLOT));
    verify(signedBlocksDao)
        .insertBlockProposal(any(), refEq(new SignedBlock(VALIDATOR_ID, SLOT, SIGNING_ROOT)));
  }

  @Test
  public void blockCanSignWhenExactlyMatchesBlock() {
    final SignedBlock signedBlock = new SignedBlock(VALIDATOR_ID, SLOT, SIGNING_ROOT);
    when(signedBlocksDao.findExistingBlock(any(), anyInt(), any()))
        .thenReturn(Optional.of(signedBlock));

    assertThat(dbSlashingProtection.maySignBlock(PUBLIC_KEY1, SIGNING_ROOT, SLOT)).isTrue();
    verify(signedBlocksDao).findExistingBlock(any(), eq(VALIDATOR_ID), eq(SLOT));
    verify(signedBlocksDao, never()).insertBlockProposal(any(), refEq(signedBlock));
  }

  @Test
  public void blockCannotSignWhenSamePublicKeyAndSlotButDifferentSigningRoot() {
    final SignedBlock signedBlock = new SignedBlock(VALIDATOR_ID, SLOT, Bytes.of(4));
    when(signedBlocksDao.findExistingBlock(any(), anyInt(), any()))
        .thenReturn(Optional.of(signedBlock));

    assertThat(dbSlashingProtection.maySignBlock(PUBLIC_KEY1, SIGNING_ROOT, SLOT)).isFalse();
    verify(signedBlocksDao).findExistingBlock(any(), eq(VALIDATOR_ID), eq(SLOT));
    verify(signedBlocksDao, never())
        .insertBlockProposal(any(), refEq(new SignedBlock(VALIDATOR_ID, SLOT, SIGNING_ROOT)));
  }

  @Test
  public void blockCannotSignWhenNoRegisteredValidator() {
    final DbSlashingProtection dbSlashingProtection =
        new DbSlashingProtection(
            db.getJdbi(), validatorsDao, signedBlocksDao, signedAttestationsDao);

    assertThatThrownBy(() -> dbSlashingProtection.maySignBlock(PUBLIC_KEY1, SIGNING_ROOT, SLOT))
        .hasMessage("Unregistered validator for " + PUBLIC_KEY1)
        .isInstanceOf(IllegalStateException.class);

    verify(signedBlocksDao, never())
        .insertBlockProposal(any(), refEq(new SignedBlock(VALIDATOR_ID, SLOT, SIGNING_ROOT)));
  }

  @Test
  public void attestationCanSignWhenExactlyMatchesExistingAttestation() {
    final SignedAttestation attestation =
        new SignedAttestation(VALIDATOR_ID, SOURCE_EPOCH, TARGET_EPOCH, SIGNING_ROOT);
    when(signedAttestationsDao.findExistingAttestation(any(), anyInt(), any()))
        .thenReturn(Optional.of(attestation));

    assertThat(
            dbSlashingProtection.maySignAttestation(
                PUBLIC_KEY1, SIGNING_ROOT, SOURCE_EPOCH, TARGET_EPOCH))
        .isTrue();
    verify(signedAttestationsDao)
        .findExistingAttestation(any(), eq(VALIDATOR_ID), eq(TARGET_EPOCH));
    verify(signedAttestationsDao, never()).insertAttestation(any(), refEq(attestation));
  }

  @Test
  public void attestationCannotSignWhenPreviousIsSurroundingAttestation() {
    final SignedAttestation attestation =
        new SignedAttestation(VALIDATOR_ID, SOURCE_EPOCH, TARGET_EPOCH, SIGNING_ROOT);
    when(signedAttestationsDao.findExistingAttestation(any(), anyInt(), any()))
        .thenReturn(Optional.empty());
    final SignedAttestation surroundingAttestation =
        new SignedAttestation(
            VALIDATOR_ID, SOURCE_EPOCH.subtract(1), TARGET_EPOCH.subtract(1), SIGNING_ROOT);
    when(signedAttestationsDao.findSurroundingAttestation(any(), anyInt(), any(), any()))
        .thenReturn(Optional.of(surroundingAttestation));

    assertThat(
            dbSlashingProtection.maySignAttestation(
                PUBLIC_KEY1, SIGNING_ROOT, SOURCE_EPOCH, TARGET_EPOCH))
        .isFalse();
    verify(signedAttestationsDao)
        .findExistingAttestation(any(), eq(VALIDATOR_ID), eq(TARGET_EPOCH));
    verify(signedAttestationsDao)
        .findSurroundingAttestation(any(), eq(VALIDATOR_ID), eq(SOURCE_EPOCH), eq(TARGET_EPOCH));
    verify(signedAttestationsDao, never()).insertAttestation(any(), refEq(attestation));
  }

  @Test
  public void attestationCannotSignWhenPreviousIsSurroundedByAttestation() {
    final SignedAttestation attestation =
        new SignedAttestation(VALIDATOR_ID, SOURCE_EPOCH, TARGET_EPOCH, SIGNING_ROOT);
    when(signedAttestationsDao.findExistingAttestation(any(), anyInt(), any()))
        .thenReturn(Optional.empty());
    when(signedAttestationsDao.findSurroundingAttestation(any(), anyInt(), any(), any()))
        .thenReturn(Optional.empty());
    final SignedAttestation surroundedAttestation =
        new SignedAttestation(VALIDATOR_ID, SOURCE_EPOCH.add(1), TARGET_EPOCH.add(1), SIGNING_ROOT);
    when(signedAttestationsDao.findSurroundedAttestation(any(), anyInt(), any(), any()))
        .thenReturn(Optional.of(surroundedAttestation));

    assertThat(
            dbSlashingProtection.maySignAttestation(
                PUBLIC_KEY1, SIGNING_ROOT, SOURCE_EPOCH, TARGET_EPOCH))
        .isFalse();
    verify(signedAttestationsDao)
        .findExistingAttestation(any(), eq(VALIDATOR_ID), eq(TARGET_EPOCH));
    verify(signedAttestationsDao)
        .findSurroundingAttestation(any(), eq(VALIDATOR_ID), eq(SOURCE_EPOCH), eq(TARGET_EPOCH));
    verify(signedAttestationsDao)
        .findSurroundedAttestation(any(), eq(VALIDATOR_ID), eq(SOURCE_EPOCH), eq(TARGET_EPOCH));
    verify(signedAttestationsDao, never()).insertAttestation(any(), refEq(attestation));
  }

  @Test
  public void attestationCanSignWhenNoSurroundingOrSurroundedByAttestation() {
    final SignedAttestation attestation =
        new SignedAttestation(VALIDATOR_ID, SOURCE_EPOCH, TARGET_EPOCH, SIGNING_ROOT);
    when(signedAttestationsDao.findExistingAttestation(any(), anyInt(), any()))
        .thenReturn(Optional.empty());
    when(signedAttestationsDao.findSurroundingAttestation(any(), anyInt(), any(), any()))
        .thenReturn(Optional.empty());
    when(signedAttestationsDao.findSurroundedAttestation(any(), anyInt(), any(), any()))
        .thenReturn(Optional.empty());

    assertThat(
            dbSlashingProtection.maySignAttestation(
                PUBLIC_KEY1, SIGNING_ROOT, SOURCE_EPOCH, TARGET_EPOCH))
        .isTrue();
    verify(signedAttestationsDao)
        .findExistingAttestation(any(), eq(VALIDATOR_ID), eq(TARGET_EPOCH));
    verify(signedAttestationsDao)
        .findSurroundingAttestation(any(), eq(VALIDATOR_ID), eq(SOURCE_EPOCH), eq(TARGET_EPOCH));
    verify(signedAttestationsDao)
        .findSurroundedAttestation(any(), eq(VALIDATOR_ID), eq(SOURCE_EPOCH), eq(TARGET_EPOCH));
    verify(signedAttestationsDao).insertAttestation(any(), refEq(attestation));
  }

  @Test
  public void attestationCannotSignWhenNoRegisteredValidator() {
    final DbSlashingProtection dbSlashingProtection =
        new DbSlashingProtection(
            db.getJdbi(), validatorsDao, signedBlocksDao, signedAttestationsDao);

    assertThatThrownBy(
            () ->
                dbSlashingProtection.maySignAttestation(
                    PUBLIC_KEY1, SIGNING_ROOT, SOURCE_EPOCH, TARGET_EPOCH))
        .hasMessage("Unregistered validator for " + PUBLIC_KEY1)
        .isInstanceOf(IllegalStateException.class);
    final SignedAttestation attestation =
        new SignedAttestation(VALIDATOR_ID, SOURCE_EPOCH, TARGET_EPOCH, SIGNING_ROOT);
    verify(signedAttestationsDao, never()).insertAttestation(any(), refEq(attestation));
  }

  @Test
  public void attestationCannotSignWhenSourceEpochGreaterThanTargetEpoch() {
    final UInt64 sourceEpoch = TARGET_EPOCH.add(1);
    final SignedAttestation attestation =
        new SignedAttestation(VALIDATOR_ID, sourceEpoch, TARGET_EPOCH, SIGNING_ROOT);

    assertThat(
            dbSlashingProtection.maySignAttestation(
                PUBLIC_KEY1, SIGNING_ROOT, sourceEpoch, TARGET_EPOCH))
        .isFalse();
    verifyNoInteractions(signedAttestationsDao);
    verify(signedAttestationsDao, never()).insertAttestation(any(), refEq(attestation));
  }

  @Test
  public void registersValidatorsThatAreNotAlreadyInDb() {
    final Map<Bytes, Integer> registeredValidators = new HashMap<>();
    registeredValidators.put(PUBLIC_KEY1, 1);
    final DbSlashingProtection dbSlashingProtection =
        new DbSlashingProtection(
            db.getJdbi(),
            validatorsDao,
            signedBlocksDao,
            signedAttestationsDao,
            registeredValidators);

    when(validatorsDao.retrieveValidators(any(), any()))
        .thenReturn(List.of(new Validator(1, PUBLIC_KEY1)));
    when(validatorsDao.registerValidators(any(), any()))
        .thenReturn(List.of(new Validator(2, PUBLIC_KEY2), new Validator(3, PUBLIC_KEY3)));
    dbSlashingProtection.registerValidators(List.of(PUBLIC_KEY1, PUBLIC_KEY2, PUBLIC_KEY3));

    assertThat(registeredValidators).hasSize(3);
    assertThat(registeredValidators)
        .isEqualTo(Map.of(PUBLIC_KEY1, 1, PUBLIC_KEY2, 2, PUBLIC_KEY3, 3));
    verify(validatorsDao)
        .retrieveValidators(any(), eq(List.of(PUBLIC_KEY1, PUBLIC_KEY2, PUBLIC_KEY3)));
    verify(validatorsDao).registerValidators(any(), eq(List.of(PUBLIC_KEY2, PUBLIC_KEY3)));
  }

  @Test
  public void slashingProtectionEnactedIfAttestationWithNullSigningRootExists() {
    when(signedAttestationsDao.findExistingAttestation(any(), anyInt(), any()))
        .thenReturn(
            Optional.of(new SignedAttestation(1, UInt64.valueOf(1), UInt64.valueOf(2), null)));

    final boolean result =
        dbSlashingProtection.maySignAttestation(
            PUBLIC_KEY1, SIGNING_ROOT, SOURCE_EPOCH, TARGET_EPOCH);

    assertThat(result).isFalse();
  }

  @Test
  public void slashingProtectionEnactedIfBlockWithNullSigningRootExists() {
    when(signedBlocksDao.findExistingBlock(any(), anyInt(), any()))
        .thenReturn(Optional.of(new SignedBlock(1, UInt64.valueOf(1), null)));

    final boolean result = dbSlashingProtection.maySignBlock(PUBLIC_KEY1, SIGNING_ROOT, SLOT);

    assertThat(result).isFalse();
  }

  @Test
  public void slashingProtectionEnactedIfBlockWithSlotLessThanMinSlot() {
    when(signedBlocksDao.minimumSlot(any(), anyInt())).thenReturn(Optional.of(UInt64.valueOf(2)));

    assertThat(dbSlashingProtection.maySignBlock(PUBLIC_KEY1, SIGNING_ROOT, UInt64.ONE)).isFalse();

    verify(signedBlocksDao).minimumSlot(any(), eq(VALIDATOR_ID));
    verify(signedBlocksDao, never()).insertBlockProposal(any(), any());
  }

  @Test
  public void slashingProtectionEnactedIfBlockWithSlotEqualToMinSlot() {
    when(signedBlocksDao.minimumSlot(any(), anyInt())).thenReturn(Optional.of(SLOT));

    assertThat(dbSlashingProtection.maySignBlock(PUBLIC_KEY1, SIGNING_ROOT, SLOT)).isFalse();

    verify(signedBlocksDao).minimumSlot(any(), eq(VALIDATOR_ID));
    verify(signedBlocksDao, never()).insertBlockProposal(any(), any());
  }

  @Test
  public void slashingProtectionEnactedIfAttestationWithSourceEpochLessThanMin() {
    when(signedAttestationsDao.minimumSourceEpoch(any(), anyInt()))
        .thenReturn(Optional.of(UInt64.valueOf(2)));

    assertThat(
            dbSlashingProtection.maySignAttestation(
                PUBLIC_KEY1, SIGNING_ROOT, UInt64.ZERO, UInt64.ZERO))
        .isFalse();

    verify(signedAttestationsDao).minimumSourceEpoch(any(), eq(VALIDATOR_ID));
    verify(signedAttestationsDao, never()).insertAttestation(any(), any());
  }

  @Test
  public void attestationCanBeSignedWithSourceEpochEqualToMin() {
    when(signedAttestationsDao.minimumSourceEpoch(any(), anyInt()))
        .thenReturn(Optional.of(SOURCE_EPOCH));

    assertThat(
            dbSlashingProtection.maySignAttestation(
                PUBLIC_KEY1, SIGNING_ROOT, SOURCE_EPOCH, TARGET_EPOCH))
        .isTrue();

    verify(signedAttestationsDao).minimumSourceEpoch(any(), eq(VALIDATOR_ID));
    final SignedAttestation attestation =
        new SignedAttestation(VALIDATOR_ID, SOURCE_EPOCH, TARGET_EPOCH, SIGNING_ROOT);
    verify(signedAttestationsDao).insertAttestation(any(), refEq(attestation));
  }

  @Test
  public void slashingProtectionEnactedIfAttestationWithTargetEpochLessThanMin() {
    when(signedAttestationsDao.minimumSourceEpoch(any(), anyInt()))
        .thenReturn(Optional.of(UInt64.valueOf(2)));
    when(signedAttestationsDao.minimumTargetEpoch(any(), anyInt()))
        .thenReturn(Optional.of(UInt64.valueOf(5)));

    assertThat(
            dbSlashingProtection.maySignAttestation(
                PUBLIC_KEY1, SIGNING_ROOT, UInt64.valueOf(3), UInt64.valueOf(4)))
        .isFalse();

    verify(signedAttestationsDao).minimumSourceEpoch(any(), eq(VALIDATOR_ID));
    verify(signedAttestationsDao).minimumTargetEpoch(any(), eq(VALIDATOR_ID));
    verify(signedAttestationsDao, never()).insertAttestation(any(), any());
  }

  @Test
  public void slashingProtectionEnactedIfAttestationWithTargetEpochEqualToMin() {
    when(signedAttestationsDao.minimumSourceEpoch(any(), anyInt()))
        .thenReturn(Optional.of(UInt64.valueOf(2)));
    when(signedAttestationsDao.minimumTargetEpoch(any(), anyInt()))
        .thenReturn(Optional.of(UInt64.valueOf(4)));

    assertThat(
            dbSlashingProtection.maySignAttestation(
                PUBLIC_KEY1, SIGNING_ROOT, UInt64.valueOf(3), UInt64.valueOf(4)))
        .isFalse();

    verify(signedAttestationsDao).minimumSourceEpoch(any(), eq(VALIDATOR_ID));
    verify(signedAttestationsDao).minimumTargetEpoch(any(), eq(VALIDATOR_ID));
    verify(signedAttestationsDao, never()).insertAttestation(any(), any());
  }
}
