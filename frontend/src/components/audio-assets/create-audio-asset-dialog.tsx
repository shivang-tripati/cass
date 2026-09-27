"use client";
/* eslint-disable @typescript-eslint/no-explicit-any */
import { useState } from "react";
import { useForm } from "react-hook-form";
import { zodResolver } from "@hookform/resolvers/zod";
import { useQueryClient } from "@tanstack/react-query";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogFooter } from "@/components/ui/dialog";
import { FieldGroup } from "@/components/ui/field";
import { Spinner } from "@/components/ui/spinner";
import { TextField } from "@/components/forms/text-field";
import { TextareaField } from "@/components/forms/textarea-field";
import { toApiError } from "@/lib/api/error";
import { createAudioAsset, audioAssetsKeys } from "@/lib/api/audio-assets";
import { createAudioAssetSchema, toCreatePayload, type CreateAudioAssetValues } from "@/lib/schemas/audio-asset-mutation";

export function CreateAudioAssetDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (o:boolean)=>void }) {
  const qc = useQueryClient();
  const [alert, setAlert] = useState<string|null>(null);
  const form = useForm<CreateAudioAssetValues>({ resolver: zodResolver(createAudioAssetSchema) as any, defaultValues: { name:"", description:"", fileName:"", contentType:"audio/mpeg", fileSize:0 } as any });
  const pending = form.formState.isSubmitting;
  function close(o:boolean){ if(!o){ form.reset(); setAlert(null);} onOpenChange(o); }
  async function onSubmit(v: CreateAudioAssetValues){
    setAlert(null);
    try{
      await createAudioAsset(toCreatePayload(v));
      toast.success("Audio asset registered");
      await qc.invalidateQueries({ queryKey: audioAssetsKeys.all });
      close(false);
    }catch(e){
      const ae = toApiError(e);
      for(const fe of ae.fieldErrors) if(fe.field in createAudioAssetSchema.shape) { form.setError(fe.field as any, {message: fe.message}); return; }
      setAlert(ae.message);
    }
  }
  return (
    <Dialog open={open} onOpenChange={close}>
      <DialogContent className="sm:max-w-lg max-h-[85vh] overflow-auto">
        <DialogHeader><DialogTitle>Register audio asset</DialogTitle></DialogHeader>
        {alert && <p role="alert" className="text-sm text-destructive">{alert}</p>}
        <form onSubmit={form.handleSubmit(onSubmit as any)} noValidate>
          <FieldGroup>
            <TextField label="Name" registration={form.register("name")} error={form.formState.errors.name?.message} />
            <TextareaField label="Description" registration={form.register("description")} error={form.formState.errors.description?.message} rows={3} />
            <TextField label="File name" placeholder="welcome.mp3" registration={form.register("fileName")} error={form.formState.errors.fileName?.message} description="No path separators." />
            <TextField label="Content type" placeholder="audio/mpeg" registration={form.register("contentType")} error={form.formState.errors.contentType?.message} />
            <TextField label="File size (bytes)" type="number" registration={form.register("fileSize")} error={form.formState.errors.fileSize?.message} />
            <TextField label="Duration (seconds)" type="number" registration={form.register("durationSeconds")} error={form.formState.errors.durationSeconds?.message} />
            <TextField label="Checksum (SHA-256 hex, optional)" placeholder="64 hex chars" registration={form.register("checksum")} error={form.formState.errors.checksum?.message} />
            <TextField label="Storage reference (optional)" registration={form.register("storageReference")} error={form.formState.errors.storageReference?.message} />
            <DialogFooter>
              <Button type="button" variant="outline" onClick={()=>close(false)} disabled={pending}>Cancel</Button>
              <Button type="submit" disabled={pending}>{pending ? <><Spinner/> Registering…</>: "Register"}</Button>
            </DialogFooter>
          </FieldGroup>
        </form>
      </DialogContent>
    </Dialog>
  );
}
