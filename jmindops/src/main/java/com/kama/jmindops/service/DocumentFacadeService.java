package com.kama.jmindops.service;

import com.kama.jmindops.model.request.CreateDocumentRequest;
import com.kama.jmindops.model.request.UpdateDocumentRequest;
import com.kama.jmindops.model.response.CreateDocumentResponse;
import com.kama.jmindops.model.response.GetDocumentsResponse;
import org.springframework.web.multipart.MultipartFile;

public interface DocumentFacadeService {
    GetDocumentsResponse getDocuments();

    GetDocumentsResponse getDocumentsByKbId(String kbId);

    CreateDocumentResponse createDocument(CreateDocumentRequest request);

    CreateDocumentResponse uploadDocument(String kbId, MultipartFile file);

    void deleteDocument(String documentId);

    void updateDocument(String documentId, UpdateDocumentRequest request);
}
