package com.haris.reporead.Controller;

import com.haris.reporead.Service.RepoService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
public class RepoReadController {

    @Autowired
        public RepoService repoService ;
    @GetMapping("/stream/v1/retrivelmodel/chat")
    public ResponseEntity<Flux<String>> streamoutputwithquestionansweradvisor(@RequestParam("question") String query
            , @RequestHeader("userid") String userid) {

         return repoService.streamoutputwithquestionansweradvisor(userid, query);




    }
    @GetMapping("/load")
    public void loadDocuments(@RequestParam("repourl") String repoUrl
                                         ) {

         repoService.FetchRepotoLoadIntoVectorStore(repoUrl);
    }


}
