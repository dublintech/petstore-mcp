package com.dublintech.mcpproto1.pet;

import com.dublintech.mcpproto1.common.ApiException;
import com.dublintech.mcpproto1.common.ApiResponse;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping(path = "/v2/pet", produces = MediaType.APPLICATION_JSON_VALUE)
public class PetController {

    private final PetService petService;

    public PetController(PetService petService) {
        this.petService = petService;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Pet addPet(@RequestBody Pet pet) {
        return petService.addPet(pet);
    }

    @PutMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public Pet updatePet(@RequestBody Pet pet) {
        return petService.updatePet(pet);
    }

    /** Accepts both {@code ?status=a&status=b} and {@code ?status=a,b}. */
    @GetMapping("/findByStatus")
    public List<Pet> findPetsByStatus(@RequestParam List<String> status) {
        return petService.findByStatus(status);
    }

    /** Deprecated in the spec; kept for compatibility. */
    @Deprecated
    @GetMapping("/findByTags")
    public List<Pet> findPetsByTags(@RequestParam List<String> tags) {
        return petService.findByTags(tags);
    }

    @GetMapping("/{petId}")
    public Pet getPetById(@PathVariable long petId) {
        return petService.getPetById(petId);
    }

    @PostMapping(path = "/{petId}", consumes = MediaType.APPLICATION_FORM_URLENCODED_VALUE)
    public ApiResponse updatePetWithForm(@PathVariable long petId,
                                         @RequestParam(required = false) String name,
                                         @RequestParam(required = false) String status) {
        petService.updatePetWithForm(petId, name, status);
        return ApiResponse.ok(String.valueOf(petId));
    }

    @DeleteMapping("/{petId}")
    public ApiResponse deletePet(@PathVariable long petId,
                                 @RequestHeader(name = "api_key", required = false) String apiKey) {
        petService.deletePet(petId);
        return ApiResponse.ok(String.valueOf(petId));
    }

    /** Accepts the upload and reports what was received; the file itself is not stored. */
    @PostMapping(path = "/{petId}/uploadImage", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse uploadFile(@PathVariable long petId,
                                  @RequestParam(required = false) String additionalMetadata,
                                  @RequestParam(required = false) MultipartFile file) {
        if (!petService.exists(petId)) {
            throw ApiException.notFound("Pet not found");
        }
        StringBuilder message = new StringBuilder("additionalMetadata: ").append(additionalMetadata);
        if (file != null && !file.isEmpty()) {
            message.append("\nFile uploaded to ./").append(file.getOriginalFilename())
                    .append(", ").append(file.getSize()).append(" bytes");
        }
        return ApiResponse.ok(message.toString());
    }
}