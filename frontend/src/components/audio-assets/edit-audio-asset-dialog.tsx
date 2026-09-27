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
import { updateAudioAsset, audioAssetsKeys } from "@/lib/api/audio-assets";
import { updateAudioAssetSchema, toUpdatePayload, type UpdateAudioAssetValues } from "@/lib/schemas/audio-asset-mutation";
import type { AudioAssetResponse } from "@/lib/api/contracts";

export function EditAudioAssetDialog({ asset, onOpenChange }: { asset: AudioAssetResponse | null; onOpenChange: (o:boolean)=>void }) {
  const qc = useQueryClient();
  const [alert, setAlert] = useState<string|null>(null);
  const form = useForm<UpdateAudioAssetValues>({ resolver: zodResolver(updateAudioAssetSchema) as any, defaultValues: { name: asset?.name ?? "", description: asset?.description ?? "" } });
  if(!asset) return null;
  // ponytail: asset null guard after useForm — single form instance, avoids conditional hook
  const pending = form.formState.isSubmitting;
  async function onSubmit(v: UpdateAudioAssetValues){
    setAlert(null);
    try{
      await updateAudioAsset(asset!.id, toUpdatePayload(v));
      toast.success("Audio asset updated");
      await qc.invalidateQueries({ queryKey: audioAssetsKeys.all });
      onOpenChange(false);
    }catch(e){
      const ae = toApiError(e);
      for(const fe of ae.fieldErrors) if(fe.field in updateAudioAssetSchema.shape) { form.setError(fe.field as any, {message: fe.message}); return; }
      setAlert(ae.message);
    }
  }
  return (
    <Dialog open onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader><DialogTitle>Edit audio asset</DialogTitle></DialogHeader>
        {alert && <p role="alert" className="text-sm text-destructive">{alert}</p>}
        <form onSubmit={form.handleSubmit(onSubmit)} noValidate>
          <FieldGroup>
            <TextField label="Name" registration={form.register("name")} error={form.formState.errors.name?.message} />
            <TextareaField label="Description" registration={form.register("description")} error={form.formState.errors.description?.message} rows={3} />
            <TextField label="File name" value={asset.fileName} disabled readOnly />
            <TextField label="Content type" value={asset.contentType} disabled readOnly />
            <DialogFooter>
              <Button type="button" variant="outline" onClick={()=>onOpenChange(false)} disabled={pending}>Cancel</Button>
              <Button type="submit" disabled={pending}>{pending ? <><Spinner/> Saving…</> : "Save"}</Button>
            </DialogFooter>
          </FieldGroup>
        </form>
      </DialogContent>
    </Dialog>
  );
}
