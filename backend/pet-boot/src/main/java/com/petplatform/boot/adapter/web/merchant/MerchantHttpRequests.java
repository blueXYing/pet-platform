package com.petplatform.boot.adapter.web.merchant;

import static com.petplatform.boot.adapter.web.merchant.MerchantHttpSupport.*;

import com.petplatform.merchant.api.dto.MerchantApplicationTypes.DraftRevisionInput;
import com.petplatform.merchant.api.dto.MerchantApplicationTypes.ManualEvidenceItem;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import tools.jackson.databind.JsonNode;

final class MerchantHttpRequests {
  private MerchantHttpRequests() {}

  private static final tools.jackson.databind.ObjectReader STRICT =
      com.petplatform.boot.config.MerchantJsonReaderFactory.strictReader();
  private static final Set<String> TYPES =
      Set.of(
          "PET_LIFE_STORE",
          "PET_HOSPITAL",
          "PET_GROOMING",
          "PET_BOARDING",
          "PET_TRAINING",
          "OTHER");

  record Draft(
      String merchantName,
      String contactName,
      String contactPhone,
      String email,
      String merchantTypeCode,
      String cityCode,
      String address,
      String longitude,
      String latitude,
      String introduction,
      List<String> storePhotoAssetIds,
      String businessLicenseAssetId,
      String idCardFrontAssetId,
      String idCardBackAssetId,
      String industryLicenseAssetId) {
    Draft {
      if (merchantTypeCode != null && !TYPES.contains(merchantTypeCode)) throw invalid();
      if (storePhotoAssetIds == null) storePhotoAssetIds = List.of();
      if (storePhotoAssetIds.size() > 6
          || new HashSet<>(storePhotoAssetIds).size() != storePhotoAssetIds.size()) throw invalid();
      storePhotoAssetIds.forEach(MerchantHttpSupport::id);
      optionalId(businessLicenseAssetId);
      optionalId(idCardFrontAssetId);
      optionalId(idCardBackAssetId);
      optionalId(industryLicenseAssetId);
      max(merchantName, 50);
      max(contactName, 20);
      max(contactPhone, 11);
      max(email, 254);
      max(cityCode, 32);
      max(address, 255);
      max(introduction, 500);
    }

    DraftRevisionInput command() {
      return new DraftRevisionInput(
          merchantName,
          contactName,
          contactPhone,
          email,
          merchantTypeCode,
          cityCode,
          address,
          decimal(longitude),
          decimal(latitude),
          introduction,
          List.copyOf(storePhotoAssetIds),
          businessLicenseAssetId,
          idCardFrontAssetId,
          idCardBackAssetId,
          industryLicenseAssetId);
    }
  }

  record Save(String expectedVersion, Draft draft) {
    Save {
      version(expectedVersion);
      if (draft == null) throw invalid();
    }
  }

  record Submit(String expectedVersion, String revisionId) {
    Submit {
      version(expectedVersion);
      id(revisionId);
    }
  }

  record TaskVersion(String expectedTaskVersion) {
    TaskVersion {
      version(expectedTaskVersion);
    }
  }

  record Evidence(
      String materialId,
      String materialSha256,
      String credentialType,
      String subjectName,
      String identifier,
      LocalDate validFrom,
      String validityKind,
      LocalDate validTo) {
    Evidence {
      id(materialId);
      hash(materialSha256);
      if (!Set.of("CREDIT_CODE", "IDENTITY_NUMBER", "INDUSTRY_LICENSE").contains(credentialType)
          || subjectName == null
          || subjectName.isEmpty()
          || subjectName.length() > 256
          || identifier == null
          || identifier.isEmpty()
          || identifier.length() > 128
          || validFrom == null
          || !Set.of("DATED", "LONG_TERM").contains(validityKind)
          || ("DATED".equals(validityKind) && validTo == null)
          || ("LONG_TERM".equals(validityKind) && validTo != null)) throw invalid();
    }

    ManualEvidenceItem command() {
      return ManualEvidenceItem.fromReference(
          materialId,
          materialSha256,
          credentialType,
          subjectName,
          identifier,
          validityKind,
          validFrom,
          validTo);
    }
  }

  record ManualVerify(
      String submissionRevisionId,
      String expectedVersion,
      String expectedTaskVersion,
      List<Evidence> evidenceItems,
      String reason,
      Boolean confirmed) {
    ManualVerify {
      id(submissionRevisionId);
      version(expectedVersion);
      version(expectedTaskVersion);
      if (evidenceItems == null
          || evidenceItems.isEmpty()
          || evidenceItems.size() > 4
          || reason == null
          || reason.length() < 10
          || reason.length() > 500
          || !Boolean.TRUE.equals(confirmed)) throw invalid();
      evidenceItems = List.copyOf(evidenceItems);
    }
  }

  record Decision(
      String submissionRevisionId,
      String expectedVersion,
      String expectedTaskVersion,
      String internalNote,
      Boolean confirmed,
      String decisionType,
      String opinion) {
    Decision {
      id(submissionRevisionId);
      version(expectedVersion);
      version(expectedTaskVersion);
      if (!Boolean.TRUE.equals(confirmed)
          || !Set.of("APPROVE", "REJECT", "REQUEST_CORRECTION").contains(decisionType)
          || (internalNote != null && internalNote.length() > 500)
          || (opinion != null && opinion.length() > 500)
          || (!"APPROVE".equals(decisionType) && (opinion == null || opinion.length() < 10)))
        throw invalid();
    }
  }

  record AgreementConsent(
      String merchantId, String agreementVersion, String contentSha256, Boolean accepted) {
    AgreementConsent {
      id(merchantId);
      hash(contentSha256);
      if (agreementVersion == null
          || agreementVersion.isEmpty()
          || agreementVersion.length() > 64
          || !Boolean.TRUE.equals(accepted)) throw invalid();
    }
  }

  private static void optionalId(String value) {
    if (value != null) id(value);
  }

  private static void max(String value, int max) {
    if (value != null && value.length() > max) throw invalid();
  }

  private static BigDecimal decimal(String value) {
    if (value == null) return null;
    try {
      if (!value.matches("-?(0|[1-9][0-9]{0,2})(\\.[0-9]{1,7})?")) throw invalid();
      return new BigDecimal(value);
    } catch (NumberFormatException e) {
      throw invalid();
    }
  }

  static <T> T decode(String json, Class<T> type) {
    try {
      JsonNode root = STRICT.readTree(json);
      if (root == null || !root.isObject()) throw invalid();
      if (type == Draft.class) draftTypes(root);
      if (type == Save.class) {
        textual(root, "expectedVersion");
        draftTypes(root.get("draft"));
      }
      if (type == Submit.class) {
        textual(root, "expectedVersion");
        textual(root, "revisionId");
      }
      if (type == TaskVersion.class) textual(root, "expectedTaskVersion");
      if (type == ManualVerify.class) {
        textual(root, "submissionRevisionId", "expectedVersion", "expectedTaskVersion", "reason");
        bool(root, "confirmed");
        JsonNode items = root.get("evidenceItems");
        if (items != null && items.isArray())
          for (JsonNode item : items) {
            textual(
                item,
                "materialId",
                "materialSha256",
                "credentialType",
                "subjectName",
                "identifier",
                "validFrom",
                "validityKind");
            optionalTextual(item, "validTo");
          }
      }
      if (type == Decision.class) {
        textual(
            root, "submissionRevisionId", "expectedVersion", "expectedTaskVersion", "decisionType");
        optionalTextual(root, "internalNote", "opinion");
        bool(root, "confirmed");
      }
      if (type == AgreementConsent.class) {
        textual(root, "merchantId", "agreementVersion", "contentSha256");
        bool(root, "accepted");
      }
      return STRICT.forType(type).readValue(root);
    } catch (RuntimeException e) {
      throw invalid();
    }
  }

  private static void draftTypes(JsonNode node) {
    if (node == null || !node.isObject()) throw invalid();
    optionalTextual(
        node,
        "merchantName",
        "contactName",
        "contactPhone",
        "email",
        "merchantTypeCode",
        "cityCode",
        "address",
        "longitude",
        "latitude",
        "introduction",
        "businessLicenseAssetId",
        "idCardFrontAssetId",
        "idCardBackAssetId",
        "industryLicenseAssetId");
    JsonNode photos = node.get("storePhotoAssetIds");
    if (photos != null) {
      if (photos.isNull()) throw invalid();
      if (!photos.isArray()) throw invalid();
      for (JsonNode photo : photos) if (!photo.isTextual()) throw invalid();
    }
  }

  private static void textual(JsonNode node, String... names) {
    for (String name : names)
      if (node.get(name) == null || !node.get(name).isTextual()) throw invalid();
  }

  private static void optionalTextual(JsonNode node, String... names) {
    for (String name : names) {
      JsonNode value = node.get(name);
      if (value != null && !value.isNull() && !value.isTextual()) throw invalid();
    }
  }

  private static void bool(JsonNode node, String name) {
    if (node.get(name) == null || !node.get(name).isBoolean()) throw invalid();
  }
}
